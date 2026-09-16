package com.cloudmusic2ktv.dlna;

import java.io.*; import java.net.*; import java.nio.charset.StandardCharsets; import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory; import org.w3c.dom.*;

public final class DlnaRenderer {
  public final String name, location; private final String controlUrl;
  private DlnaRenderer(String n,String l,String c){name=n;location=l;controlUrl=c;}
  public static List<DlnaRenderer> discover(int timeoutMs) throws IOException {
    List<DlnaRenderer> out=new ArrayList<>(); DatagramSocket s=new DatagramSocket(); s.setSoTimeout(timeoutMs);
    String q="M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
    byte[] b=q.getBytes(StandardCharsets.US_ASCII); s.send(new DatagramPacket(b,b.length,InetAddress.getByName("239.255.255.250"),1900));
    Set<String> seen=new HashSet<>(); long end=System.currentTimeMillis()+timeoutMs;
    while(System.currentTimeMillis()<end){try{byte[] x=new byte[4096]; DatagramPacket p=new DatagramPacket(x,x.length); s.receive(p); String h=new String(p.getData(),0,p.getLength(),StandardCharsets.ISO_8859_1); String loc=header(h,"location"); if(loc!=null&&seen.add(loc)){DlnaRenderer r=fromDescription(loc);if(r!=null)out.add(r);}}catch(SocketTimeoutException e){break;}}
    s.close(); return out;
  }
  private static DlnaRenderer fromDescription(String loc){try{HttpURLConnection c=(HttpURLConnection)new URL(loc).openConnection();c.setConnectTimeout(3000);c.setReadTimeout(3000);Document d=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(c.getInputStream());String name=text(d,"friendlyName"), base=loc;String control=null;NodeList n=d.getElementsByTagNameNS("*","service");for(int i=0;i<n.getLength();i++){Element e=(Element)n.item(i);if("urn:schemas-upnp-org:service:AVTransport:1".equals(text(e,"serviceType"))){control=text(e,"controlURL");break;}}if(control==null)return null;return new DlnaRenderer(name,loc,new URL(new URL(base),control).toString());}catch(Exception e){return null;}}
  private static String text(Node n,String tag){NodeList x=((Document)n).getElementsByTagNameNS("*",tag);return x.getLength()==0?null:x.item(0).getTextContent().trim();} private static String text(Element n,String tag){NodeList x=n.getElementsByTagNameNS("*",tag);return x.getLength()==0?null:x.item(0).getTextContent().trim();}
  private static String header(String h,String k){for(String l:h.split("\\r?\\n"))if(l.toLowerCase(Locale.ROOT).startsWith(k+":"))return l.substring(k.length()+1).trim();return null;}
  public void play(String mediaUrl) throws IOException {soap("SetAVTransportURI","<InstanceID>0</InstanceID><CurrentURI>"+esc(mediaUrl)+"</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>");soap("Play","<InstanceID>0</InstanceID><Speed>1</Speed>");}
  private void soap(String action,String body)throws IOException{String xml="<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:"+action+" xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"+body+"</u:"+action+"></s:Body></s:Envelope>";HttpURLConnection c=(HttpURLConnection)new URL(controlUrl).openConnection();c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","text/xml; charset=\"utf-8\"");c.setRequestProperty("SOAPACTION","\"urn:schemas-upnp-org:service:AVTransport:1#"+action+"\"");c.getOutputStream().write(xml.getBytes(StandardCharsets.UTF_8));if(c.getResponseCode()/100!=2)throw new IOException("DLNA SOAP "+c.getResponseCode());}
  private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
  public String toString(){return name==null?location:name;}
}
