#!/usr/bin/env python3
"""Small dependency-free UPnP/DLNA MediaRenderer for local testing.

It advertises itself over SSDP, serves a device description, records SOAP
calls, and optionally forwards the received media URL to the bundled mpv
player.  Use /state to inspect the current request and playback state.
"""

from __future__ import annotations

import argparse
import http.client
import html
import http.server
import json
import os
from pathlib import Path
import socket
import socketserver
import struct
import threading
import time
import uuid
import re
from typing import Callable
from urllib.parse import urlparse

from mpv_controller import MpvController, MpvUnavailable


SSDP_ADDRESS = ("239.255.255.250", 1900)
DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
DEVICE_URN = "uuid:cloudmusic2ktv-mock-renderer"
AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"


class RendererState:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.last_uri = ""
        self.last_metadata = ""
        self.last_action = ""
        self.transport_state = "STOPPED"
        self.transport_status = "OK"
        self.position = 0.0
        self.duration = 0.0
        self.request_count = 0
        self.requests: list[dict[str, object]] = []
        self.subscribers: dict[str, str] = {}
        self._sequence = 0
        self.on_state_change: Callable[[], None] | None = None

    def record(self, action: str, uri: str, body: str) -> None:
        with self.lock:
            self.last_action = action
            if uri:
                self.last_uri = uri
            if action == "SetAVTransportURI":
                # Match Macast's handshake: load the URI immediately but
                # report PAUSED_PLAYBACK until the sender confirms the cast
                # session with a Play action.
                self.transport_state = "PAUSED_PLAYBACK"
                self.transport_status = "OK"
                self.position = 0.0
                self.duration = 0.0
                match = re.search(r"<CurrentURIMetaData[^>]*>(.*?)</CurrentURIMetaData>", body, re.DOTALL)
                if match:
                    self.last_metadata = html.unescape(match.group(1).strip())
            elif action == "Play":
                self.transport_state = "PLAYING"
            elif action == "Pause":
                self.transport_state = "PAUSED_PLAYBACK"
            elif action in ("Stop", "SetNextAVTransportURI"):
                self.transport_state = "STOPPED"
            self.request_count += 1
            self.requests.append({"action": action, "uri": uri, "time": time.time()})
            del self.requests[:-20]

    def set_transport_state(self, value: str, status: str = "OK", notify: bool = False) -> None:
        with self.lock:
            changed = self.transport_state != value or self.transport_status != status
            self.transport_state = value
            self.transport_status = status
        if changed and notify and self.on_state_change:
            self.on_state_change()

    def set_position(self, position: float | None = None, duration: float | None = None) -> None:
        with self.lock:
            if position is not None:
                self.position = max(0.0, position)
            if duration is not None:
                self.duration = max(0.0, duration)

    def subscribe(self, sid: str, callback: str) -> None:
        with self.lock:
            self.subscribers[sid] = callback

    def unsubscribe(self, sid: str) -> None:
        with self.lock:
            self.subscribers.pop(sid, None)

    def event_snapshot(self) -> tuple[dict[str, str], str, float, float, int]:
        with self.lock:
            sequence = self._sequence
            self._sequence = (self._sequence + 1) % (2**32)
            return dict(self.subscribers), self.transport_state, self.position, self.duration, sequence

    def snapshot(self) -> dict[str, object]:
        with self.lock:
            return {
                "last_action": self.last_action,
                "last_uri": self.last_uri,
                "last_metadata": self.last_metadata,
                "transport_state": self.transport_state,
                "transport_status": self.transport_status,
                "position": self.position,
                "duration": self.duration,
                "subscriber_count": len(self.subscribers),
                "request_count": self.request_count,
                "requests": list(self.requests),
            }


def local_ip() -> str:
    """Return the address reachable from another device on the LAN."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect(("192.168.1.1", 9))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def device_description(base_url: str) -> bytes:
    services = """\
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>dlna/AVTransport.xml</SCPDURL>
        <controlURL>AVTransport/action</controlURL>
        <eventSubURL>AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>dlna/RenderingControl.xml</SCPDURL>
        <controlURL>RenderingControl/action</controlURL>
        <eventSubURL>RenderingControl/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>dlna/ConnectionManager.xml</SCPDURL>
        <controlURL>ConnectionManager/action</controlURL>
        <eventSubURL>ConnectionManager/event</eventSubURL>
      </service>"""
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>{DEVICE_TYPE}</deviceType>
    <friendlyName>CloudMusic2KTV Mock Renderer</friendlyName>
    <manufacturer>xfangfang</manufacturer>
    <manufacturerURL>https://github.com/xfangfang</manufacturerURL>
    <modelDescription>AVTransport Media Renderer</modelDescription>
    <modelName>Macast</modelName>
    <modelNumber>0.0</modelNumber>
    <modelURL>https://xfangfang.github.io/Macast</modelURL>
    <serialNumber>1024</serialNumber>
    <dlna:X_DLNADOC xmlns:dlna="urn:schemas-dlna-org:device-1-0">DMR-1.50</dlna:X_DLNADOC>
    <UDN>{DEVICE_URN}</UDN>
    <serviceList>
{services}
    </serviceList>
  </device>
</root>
'''
    return xml.encode("utf-8")


def format_duration(seconds: float) -> str:
    """Format seconds using the UPnP ``HH:MM:SS`` duration form."""
    total = max(0, int(seconds))
    hours, remainder = divmod(total, 3600)
    minutes, seconds = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{seconds:02d}"


def soap_response(action: str, namespace: str = AV_TRANSPORT, state: RendererState | None = None) -> bytes:
    result = ""
    if action == "GetVolume":
        result = "<CurrentVolume>50</CurrentVolume>"
    elif action == "GetMute":
        result = "<CurrentMute>0</CurrentMute>"
    elif action == "GetVolumeDB":
        result = "<CurrentVolume>0</CurrentVolume>"
    elif action == "GetVolumeDBRange":
        result = "<MinValue>-100</MinValue><MaxValue>0</MaxValue>"
    elif action == "ListPresets":
        result = "<CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>"
    elif action == "GetProtocolInfo":
        result = "<Source></Source><Sink>http-get:*:video/mp4:*</Sink>"
    elif action == "GetCurrentConnectionIDs":
        result = "<ConnectionIDs>0</ConnectionIDs>"
    elif action == "GetCurrentConnectionInfo":
        result = (
            "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>"
            "<ProtocolInfo>http-get:*:video/mp4:*</ProtocolInfo>"
            "<PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID>"
            "<Direction>Input</Direction><Status>OK</Status>"
        )
    elif action == "GetCurrentTransportActions":
        result = "<Actions>Play,Pause,Stop,Seek</Actions>"
    elif action == "GetTransportInfo":
        info = state.snapshot() if state else {"transport_state": "STOPPED", "transport_status": "OK"}
        result = f"<CurrentTransportState>{info['transport_state']}</CurrentTransportState><CurrentTransportStatus>{info['transport_status']}</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
    elif action == "GetPositionInfo":
        info = state.snapshot() if state else {"position": 0.0, "duration": 0.0, "last_uri": "", "last_metadata": ""}
        duration = float(info["duration"])
        duration_text = format_duration(duration) if duration > 0 else ""
        result = (
            f"<Track>0</Track><TrackDuration>{duration_text}</TrackDuration>"
            f"<TrackMetaData>{html.escape(str(info['last_metadata']))}</TrackMetaData>"
            f"<TrackURI>{html.escape(str(info['last_uri']))}</TrackURI>"
            f"<RelTime>{format_duration(float(info['position']))}</RelTime><AbsTime>{format_duration(float(info['position']))}</AbsTime>"
            "<RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount>"
        )
    elif action == "GetMediaInfo":
        info = state.snapshot() if state else {"duration": 0.0, "last_uri": "", "last_metadata": ""}
        duration = float(info["duration"])
        duration_text = format_duration(duration) if duration > 0 else ""
        result = (
            f"<NrTracks>1</NrTracks><MediaDuration>{duration_text}</MediaDuration>"
            f"<CurrentURI>{html.escape(str(info['last_uri']))}</CurrentURI>"
            f"<CurrentURIMetaData>{html.escape(str(info['last_metadata']))}</CurrentURIMetaData>"
            "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
            "<PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
            "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>"
        )
    elif action == "GetDeviceCapabilities":
        result = "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia><RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>"
    elif action == "GetTransportSettings":
        result = "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body><u:{action}Response xmlns:u="{namespace}">{result}</u:{action}Response></s:Body>
</s:Envelope>
'''.encode("utf-8")


def send_event(
    callback: str,
    sid: str,
    transport_state: str,
    position: float,
    duration: float,
    sequence: int,
) -> None:
    """Send a UPnP LastChange event to a subscribed controller."""
    try:
        parsed = urlparse(callback)
        if parsed.scheme != "http" or not parsed.hostname:
            return
        event = (
            '<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">'
            '<InstanceID val="0">'
            f'<TransportState val="{html.escape(transport_state, quote=True)}"/>'
            '<TransportStatus val="OK"/>'
            '<CurrentTrack val="1"/>'
            '<NumberOfTracks val="1"/>'
            f'<CurrentTrackDuration val="{format_duration(duration)}"/>'
            f'<CurrentMediaDuration val="{format_duration(duration)}"/>'
            '</InstanceID></Event>'
        )
        body = f'''<?xml version="1.0" encoding="utf-8"?>
<e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
  <e:property><LastChange>{html.escape(event, quote=True)}</LastChange></e:property>
</e:propertyset>'''.encode("utf-8")
        connection = http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=2)
        connection.request("NOTIFY", parsed.path or "/", body=body, headers={
            "NT": "upnp:event",
            "NTS": "upnp:propchange",
            "SID": sid,
            "SEQ": str(sequence),
            "Content-Type": 'text/xml; charset="utf-8"',
            "Content-Length": str(len(body)),
        })
        response = connection.getresponse()
        response.read()
        connection.close()
        print(f"NOTIFY {callback} -> HTTP {response.status}", flush=True)
    except Exception as error:
        print(f"NOTIFY {callback} failed: {error}", flush=True)


def scpd_description(service: str) -> bytes:
    """Return an SCPD document with the AVTransport state/actions used by phones."""
    # Use the complete Macast service descriptions when the reference checkout
    # is present. OPPO Video chooses its control sequence from this action and
    # state table; the compact hand-written table is only a fallback.
    reference = Path(__file__).with_name("Macast-reference") / "macast" / "xml" / f"{service}.xml"
    if reference.is_file():
        return reference.read_bytes()
    if service == "AVTransport":
        actions = """
      <action><name>SetAVTransportURI</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
      </argumentList></action>
      <action><name>Play</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList></action>"""
        actions += """
      <action><name>Pause</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList></action>
      <action><name>Stop</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList></action>
      <action><name>Seek</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
        <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
      </argumentList></action>
      <action><name>GetTransportInfo</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
        <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
        <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList></action>
      <action><name>GetPositionInfo</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
        <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
        <argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument>
        <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument>
        <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
        <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
        <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
        <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
      </argumentList></action>"""
        variables = """
      <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>uri</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>"""
        variables += """
      <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>CurrentTrackURI</name><dataType>uri</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>"""
        variables += """
      <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>"""
    elif service == "RenderingControl":
        actions = """
      <action><name>GetVolume</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList></action>
      <action><name>SetVolume</name><argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList></action>"""
        variables = """
      <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType></stateVariable>"""
    else:
        actions = """
      <action><name>GetProtocolInfo</name><argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList></action>
      <action><name>GetCurrentConnectionIDs</name><argumentList>
        <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
      </argumentList></action>"""
        variables = """
      <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
      <stateVariable sendEvents="no"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>"""
    return f'''<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-control-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>{actions}
  </actionList>
  <serviceStateTable>{variables}
  </serviceStateTable>
</scpd>
'''.encode("utf-8")


def extract_tag(body: str, tag: str) -> str | None:
    match = re.search(rf"<{re.escape(tag)}(?:\s[^>]*)?>(.*?)</{re.escape(tag)}>", body, re.DOTALL)
    return html.unescape(match.group(1).strip()) if match else None


def parse_time_value(value: str) -> float:
    """Parse a UPnP time value such as ``00:01:23`` or seconds."""
    if ":" not in value:
        return float(value)
    parts = value.split(":")
    if len(parts) != 3:
        raise ValueError(f"invalid time value: {value}")
    hours, minutes, seconds = parts
    return int(hours) * 3600 + int(minutes) * 60 + float(seconds)


class HttpHandler(http.server.BaseHTTPRequestHandler):
    server_version = "CloudMusic2KTVMock/1.0"

    @property
    def state(self) -> RendererState:
        return self.server.state  # type: ignore[attr-defined]

    @property
    def player(self) -> MpvController | None:
        return self.server.player  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path in ("/device.xml", "/description.xml"):
            body = device_description(self.server.base_url).replace(
                self.server.base_url.encode(), self.server.base_url.encode()
            )
            self.send_response(200)
            self.send_header("Content-Type", "text/xml; charset=utf-8")
        elif path == "/state":
            snapshot = self.state.snapshot()
            if self.player is not None:
                snapshot["mpv"] = self.player.snapshot()
            body = json.dumps(snapshot, ensure_ascii=False, indent=2).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
        elif path.endswith(".xml") and (path.startswith("/upnp/") or path.startswith("/dlna/")):
            service = "AVTransport" if "AVTransport" in path else (
                "RenderingControl" if "RenderingControl" in path else "ConnectionManager"
            )
            body = scpd_description(service)
            self.send_response(200)
            self.send_header("Content-Type", "text/xml; charset=utf-8")
        else:
            self.send_error(404)
            return
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if not (
            path.endswith("/AVTransport/action")
            or path.endswith("/RenderingControl/action")
            or path.endswith("/ConnectionManager/action")
            or path.startswith("/upnp/control/")
        ):
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        action_header = self.headers.get("SOAPACTION", "").strip('"')
        action = action_header.rsplit("#", 1)[-1] if "#" in action_header else ""
        if not action:
            marker = "Action"  # keep malformed requests visible in /state
            action = marker
        uri = ""
        if action == "SetAVTransportURI":
            match = re.search(r"<CurrentURI[^>]*>(.*?)</CurrentURI>", body, re.DOTALL)
            if match:
                uri = html.unescape(match.group(1).strip())
        self.state.record(action, uri, body)
        print(f"SOAP {action}" + (f" URI={uri}" if uri else ""), flush=True)
        try:
            if self.player is not None:
                if action == "SetAVTransportURI" and uri:
                    self.player.set_uri(uri)
                elif action == "Play":
                    self.player.play()
                elif action == "Pause":
                    self.player.pause()
                elif action == "Stop":
                    self.player.stop()
                elif action == "SetVolume":
                    desired = extract_tag(body, "DesiredVolume")
                    if desired is not None:
                        self.player.set_volume(int(float(desired)))
                elif action == "Seek":
                    target = extract_tag(body, "Target")
                    if target:
                        self.player.seek(parse_time_value(target), "absolute")
        except (MpvUnavailable, OSError, ValueError) as error:
            self.state.set_transport_state("STOPPED", "ERROR")
            print(f"mpv {action} failed: {error}", flush=True)
        namespace = AV_TRANSPORT
        if "renderingcontrol" in path.lower():
            namespace = RENDERING_CONTROL
        elif "connectionmanager" in path.lower():
            namespace = CONNECTION_MANAGER
        response = soap_response(action, namespace, self.state)
        self.send_response(200)
        self.send_header("Content-Type", 'text/xml; charset="utf-8"')
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

        # LastChange is the part that makes the phone keep a real cast session.
        # Send it only after the SOAP response has been written so a slow callback
        # cannot delay the controller's action request.
        if action in {
            "SetAVTransportURI", "Play", "Pause", "Stop", "SetNextAVTransportURI"
        }:
            self.notify_subscribers()

    def do_SUBSCRIBE(self) -> None:  # noqa: N802
        """Accept UPnP event subscriptions used by some phone renderers."""
        event_path = urlparse(self.path).path
        if not (
            event_path.endswith("/AVTransport/event")
            or event_path.endswith("/RenderingControl/event")
            or event_path.startswith("/upnp/event/")
        ):
            self.send_error(404)
            return
        sid = self.headers.get("SID") or f"uuid:{uuid.uuid4()}"
        callback_header = self.headers.get("CALLBACK", "")
        match = re.search(r"<([^>]+)>", callback_header)
        callback = match.group(1) if match else callback_header.strip().strip("<>")
        path = urlparse(self.path).path
        if callback and (path.endswith("/AVTransport/event") or path.endswith("/avtransport")):
            self.state.subscribe(sid, callback)
        self.send_response(200)
        self.send_header("SID", sid)
        self.send_header("TIMEOUT", "Second-1800")
        self.send_header("Content-Length", "0")
        self.end_headers()
        print(f"SUBSCRIBE {self.path}", flush=True)
        if callback and (path.endswith("/AVTransport/event") or path.endswith("/avtransport")):
            _, transport_state, position, duration, sequence = self.state.event_snapshot()
            threading.Thread(
                target=send_event,
                args=(callback, sid, transport_state, position, duration, sequence),
                daemon=True,
            ).start()

    def do_UNSUBSCRIBE(self) -> None:  # noqa: N802
        sid = self.headers.get("SID", "")
        self.state.unsubscribe(sid)
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def notify_subscribers(self) -> None:
        self.notify_subscribers_for_state(self.state)

    @staticmethod
    def notify_subscribers_for_state(state: RendererState) -> None:
        subscribers, transport_state, position, duration, sequence = state.event_snapshot()
        for sid, callback in subscribers.items():
            threading.Thread(
                target=send_event,
                args=(callback, sid, transport_state, position, duration, sequence),
                daemon=True,
            ).start()

    def log_message(self, format: str, *args: object) -> None:
        print(f"HTTP {self.address_string()} - {format % args}", flush=True)


class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def ssdp_loop(http_port: int, advertise_ip: str, stop: threading.Event) -> None:
    location = f"http://{advertise_ip}:{http_port}/device.xml"
    server_header = "Windows/10.0 UPnP/1.1 cloudmusic2ktv-mock/1.0"
    response_template = ("HTTP/1.1 200 OK\r\n"
                         "CACHE-CONTROL: max-age=1800\r\n"
                         "EXT:\r\n"
                         f"LOCATION: {location}\r\n"
                         f"SERVER: {server_header}\r\n"
                         "ST: {target}\r\n"
                         "USN: {usn}\r\n\r\n")
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", 1900))
    except OSError as error:
        raise RuntimeError("无法绑定 UDP 1900；可能已有 UPnP 服务占用该端口") from error
    # M-SEARCH is sent to the SSDP multicast group.  Joining the group is
    # required on Windows; binding to 0.0.0.0 alone is not sufficient.
    membership = struct.pack("4s4s", socket.inet_aton(SSDP_ADDRESS[0]), socket.inet_aton("0.0.0.0"))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
    try:
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(advertise_ip))
    except OSError:
        pass
    sock.settimeout(1.0)
    print(f"SSDP listening on 0.0.0.0:1900, advertising {location}", flush=True)
    notify_types = ("upnp:rootdevice", DEVICE_TYPE, AV_TRANSPORT, RENDERING_CONTROL, CONNECTION_MANAGER)

    def send_alive() -> None:
        for target in (DEVICE_URN,) + notify_types:
            usn = DEVICE_URN if target == DEVICE_URN else f"{DEVICE_URN}::{target}"
            packet = ("NOTIFY * HTTP/1.1\r\n"
                      f"HOST: {SSDP_ADDRESS[0]}:{SSDP_ADDRESS[1]}\r\n"
                      "CACHE-CONTROL: max-age=1800\r\n"
                      f"LOCATION: {location}\r\n"
                      f"NT: {target}\r\n"
                      "NTS: ssdp:alive\r\n"
                      f"USN: {usn}\r\n"
                      f"SERVER: {server_header}\r\n\r\n").encode("ascii")
            try:
                sock.sendto(packet, SSDP_ADDRESS)
            except OSError:
                pass

    send_alive()
    last_alive = time.monotonic()
    try:
        while not stop.is_set():
            if time.monotonic() - last_alive >= 900:
                send_alive()
                last_alive = time.monotonic()
            try:
                packet, address = sock.recvfrom(8192)
            except socket.timeout:
                continue
            text = packet.decode("iso-8859-1", errors="replace")
            if not text.upper().startswith("M-SEARCH"):
                continue
            headers = {}
            for line in text.splitlines()[1:]:
                if ":" in line:
                    key, value = line.split(":", 1)
                    headers[key.strip().lower()] = value.strip()
            target = headers.get("st", "")
            if target.lower() in (DEVICE_TYPE.lower(), AV_TRANSPORT.lower(), RENDERING_CONTROL.lower(), CONNECTION_MANAGER.lower(), "ssdp:all", "upnp:rootdevice"):
                response = response_template.format(
                    target=target or DEVICE_TYPE,
                    usn=f"{DEVICE_URN}::{target or DEVICE_TYPE}",
                ).encode("ascii")
                sock.sendto(response, address)
                print(f"SSDP reply to {address[0]}:{address[1]} ST={target}", flush=True)
    finally:
        sock.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="", help="HTTP bind address (default: all interfaces)")
    parser.add_argument("--advertise-ip", default="", help="LAN address placed in LOCATION")
    parser.add_argument("--port", type=int, default=8765, help="HTTP port")
    default_mpv = Path(__file__).with_name("mpv") / ("mpv.exe" if os.name == "nt" else "mpv")
    parser.add_argument("--mpv-path", default=str(default_mpv), help="mpv executable (default: bundled tools/mpv)")
    parser.add_argument("--no-mpv", action="store_true", help="only emulate DLNA control; do not launch mpv")
    args = parser.parse_args()
    advertise_ip = args.advertise_ip or local_ip()
    state = RendererState()
    httpd = ThreadingHTTPServer((args.host, args.port), HttpHandler)
    httpd.state = state  # type: ignore[attr-defined]
    httpd.base_url = f"http://{advertise_ip}:{args.port}"  # type: ignore[attr-defined]
    state.on_state_change = lambda: HttpHandler.notify_subscribers_for_state(state)
    player: MpvController | None = None
    if not args.no_mpv:
        player = MpvController(
            args.mpv_path,
            on_state_change=lambda value: state.set_transport_state(value, notify=True),
            on_position_change=state.set_position,
        )
        httpd.player = player  # type: ignore[attr-defined]
    else:
        httpd.player = None  # type: ignore[attr-defined]
    stop = threading.Event()
    thread = threading.Thread(target=ssdp_loop, args=(args.port, advertise_ip, stop), daemon=True)
    thread.start()
    print(f"Mock renderer ready: {httpd.base_url}/device.xml", flush=True)
    print(f"Inspect state: {httpd.base_url}/state", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        if player is not None:
            player.close()
        httpd.shutdown()
        httpd.server_close()


if __name__ == "__main__":
    main()
