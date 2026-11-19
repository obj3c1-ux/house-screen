package au.house.screen;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

/**
 * Sends this tablet's microphone to the Pi on the home network, so Aurelius can hear people in this room
 * and knows which room the voice came from. Plain 16 kHz mono sound, in small pieces, over the local network only.
 * The worker tells the app where the Pi is and gives it the key the Pi expects.
 */
class MicStreamer extends Thread {
    interface Status { void set(String s); }

    private final String link, room;
    private final Status status;
    private final Context ctx;
    private volatile boolean running = true;

    MicStreamer(Context ctx, String link, String room, Status status) {
        super("house-mic");
        this.ctx = ctx.getApplicationContext(); this.link = link; this.room = room; this.status = status;
        setDaemon(true);
    }

    void shutdown() { running = false; interrupt(); }

    @Override public void run() {
        long wait = 3000;
        while (running) {
            try {
                stream();
                wait = 3000;
            } catch (Exception e) {
                status.set("Mic: " + e.getMessage());
            }
            if (!running) break;
            try { Thread.sleep(wait); } catch (InterruptedException ie) { if (!running) break; }
            wait = Math.min(60000, wait * 2);
        }
    }

    /** her replies come back down the same connection: S (start: "rate channels"), D (sound), E (end), X (stop now) */
    private void playLoop(Socket s) {
        AudioTrack track = null;
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            while (running) {
                int type = in.readUnsignedByte();
                int len = in.readInt();
                byte[] buf = new byte[Math.max(0, len)];
                in.readFully(buf);
                if (type == 'S') {
                    if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) { } }
                    String[] p = new String(buf, "UTF-8").trim().split(" ");
                    int rate = Integer.parseInt(p[0]), ch = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                    int cfg = ch == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
                    int min = AudioTrack.getMinBufferSize(rate, cfg, AudioFormat.ENCODING_PCM_16BIT);
                    track = new AudioTrack(AudioManager.STREAM_MUSIC, rate, cfg, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate), AudioTrack.MODE_STREAM);
                    wakeVolume();
                    track.play();
                } else if (type == 'D' && track != null) {
                    track.write(buf, 0, buf.length);
                } else if (type == 'E' && track != null) {
                    try { track.stop(); } catch (Exception ignored) { }        // plays out what was written, then stops
                } else if (type == 'X' && track != null) {
                    try { track.pause(); track.flush(); track.stop(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            // the connection closed: the mic side reconnects
        } finally {
            if (track != null) { try { track.release(); } catch (Exception ignored) { } }
        }
    }

    /** a silent tablet would make her mute: if the media volume is right down, bring it up to about 60% */
    private void wakeVolume() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < max / 4) am.setStreamVolume(AudioManager.STREAM_MUSIC, (int) (max * .6), 0);
        } catch (Exception ignored) { }
    }

    private JSONObject askWorker() throws Exception {
        Uri u = Uri.parse(link);
        String k = u.getQueryParameter("k");
        if (k == null || k.isEmpty()) throw new Exception("no code in the screen link");
        URL url = new URL(u.getScheme() + "://" + u.getAuthority() + "/api/kiosk/mic?k=" + Uri.encode(k));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line; while ((line = r.readLine()) != null) sb.append(line);
        r.close(); c.disconnect();
        JSONObject j = new JSONObject(sb.toString());
        if (code >= 400) throw new Exception(j.optString("error", "worker said " + code));
        if (j.optString("ip", "").isEmpty()) throw new Exception("the Pi has not told the house its address yet (update the Pi)");
        return j;
    }

    private void stream() throws Exception {
        JSONObject j = askWorker();
        String ip = j.getString("ip"); int port = j.optInt("port", 8765); String token = j.getString("token");
        final int rate = 16000, chunk = 1024;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, chunk * 2 * 8));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); throw new Exception("microphone not available (allow it in the app's permissions)"); }
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(ip, port), 6000);
            s.setSoTimeout(8000);
            OutputStream out = s.getOutputStream();
            out.write(("HOUSE1 " + room + " " + token + " " + rate + "\n").getBytes("UTF-8")); out.flush();
            InputStream in = s.getInputStream();
            StringBuilder ok = new StringBuilder(); int ch;
            while ((ch = in.read()) != -1 && ch != '\n' && ok.length() < 40) ok.append((char) ch);
            if (!ok.toString().startsWith("OK")) throw new Exception("the Pi refused the tablet (" + ok + ")");
            s.setSoTimeout(0);              // replies can come at any time: the voice reader waits as long as it needs
            rec.startRecording();
            status.set("Mic: sending to Aurelius (" + ip + ")");
            final Socket sock = s;
            Thread player = new Thread(new Runnable() { public void run() { playLoop(sock); } }, "house-voice");
            player.setDaemon(true); player.start();
            byte[] buf = new byte[chunk * 2];
            while (running) {
                int got = 0;
                while (got < buf.length && running) { int n = rec.read(buf, got, buf.length - got); if (n <= 0) throw new Exception("microphone stopped"); got += n; }
                out.write(buf, 0, got);
            }
        } finally {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            try { s.close(); } catch (Exception ignored) { }
        }
    }
}
