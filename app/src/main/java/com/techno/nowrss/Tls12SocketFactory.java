package com.techno.nowrss;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Enables TLS 1.1/1.2 on Android 4.1-4.4, where they exist but are off by default. */
public class Tls12SocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory d;

    public Tls12SocketFactory(SSLSocketFactory delegate) { d = delegate; }

    private Socket fix(Socket s) {
        if (s instanceof SSLSocket) {
            try {
                ((SSLSocket) s).setEnabledProtocols(new String[] {"TLSv1", "TLSv1.1", "TLSv1.2"});
            } catch (Exception ignored) {
            }
        }
        return s;
    }

    public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
    public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
    public Socket createSocket(Socket s, String h, int p, boolean a) throws IOException {
        return fix(d.createSocket(s, h, p, a));
    }
    public Socket createSocket(String h, int p) throws IOException { return fix(d.createSocket(h, p)); }
    public Socket createSocket(String h, int p, InetAddress la, int lp) throws IOException {
        return fix(d.createSocket(h, p, la, lp));
    }
    public Socket createSocket(InetAddress h, int p) throws IOException { return fix(d.createSocket(h, p)); }
    public Socket createSocket(InetAddress h, int p, InetAddress la, int lp) throws IOException {
        return fix(d.createSocket(h, p, la, lp));
    }
}
