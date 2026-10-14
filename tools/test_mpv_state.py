"""Regression checks for playback events without a SOAP Play handshake."""

import unittest
import time
from unittest.mock import patch

from mock_dlna_renderer import RendererState, soap_response
from mpv_controller import MpvController


class PlaybackStateTest(unittest.TestCase):
    def setUp(self):
        self.state = RendererState()
        self.notifications = []
        self.state.on_state_change = lambda: self.notifications.append(
            self.state.snapshot()["transport_state"]
        )
        self.player = MpvController(
            "unused",
            on_state_change=lambda value: self.state.set_transport_state(value, notify=True),
        )

    def event(self, event_name, **fields):
        self.player._handle_event({"event": event_name, **fields})

    def load_without_play(self):
        self.state.record("SetAVTransportURI", "http://phone/video", "")
        with patch.object(self.player, "start"), patch.object(self.player, "_send") as send:
            self.player.set_uri("http://phone/video")
            self.assertEqual(send.call_args_list[-1].args[0], ["set_property", "pause", False])
        # Order recorded in the failing phone session on 2026-10-14.
        self.event("property-change", name="pause", data=False)
        self.assertEqual(self.notifications, [])
        self.event("start-file")
        self.event("file-loaded")
        self.event("playback-restart")

    def test_actual_playback_updates_soap_and_notifications_without_play(self):
        self.load_without_play()
        self.assertEqual(self.notifications, ["PLAYING"])
        response = soap_response("GetTransportInfo", state=self.state)
        self.assertIn(b"s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"", response)
        self.assertIn(b"<CurrentTransportState>PLAYING</CurrentTransportState>", response)

    def test_local_pause_and_seek_preserve_paused_state(self):
        self.load_without_play()
        self.event("property-change", name="pause", data=True)
        self.event("playback-restart")
        self.assertEqual(self.state.snapshot()["transport_state"], "PAUSED_PLAYBACK")
        self.event("property-change", name="pause", data=False)
        self.assertEqual(self.state.snapshot()["transport_state"], "PLAYING")

    def test_idle_events_do_not_revive_ended_playback(self):
        self.load_without_play()
        self.event("end-file", reason="eof")
        self.event("property-change", name="pause", data=False)
        self.event("playback-restart")
        self.assertEqual(self.state.snapshot()["transport_state"], "STOPPED")

    def test_gena_replaces_and_expires_stale_subscription(self):
        self.state.subscribe("sid-old", "http://127.0.0.1:1001/event", "AVTransport", 1800)
        self.state.subscribe("sid-new", "http://127.0.0.1:1001/event", "AVTransport", 1800)
        self.assertNotIn("sid-old", self.state.subscribers)
        self.assertIn("sid-new", self.state.subscribers)
        self.state.subscribers["sid-new"].expires = time.monotonic() - 1
        self.state.expire_subscriptions()
        self.assertEqual(self.state.snapshot()["subscriber_count"], 0)


if __name__ == "__main__":
    unittest.main()
