
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.RandomAccessFile;
import java.io.BufferedInputStream;
import java.io.Closeable;

public class Limny {

    public int PARTS = 6;
    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    static final long MB500  = 524288000;

    public static void main(String[] args) {
        new Limny().start(args[0]);
    }

    ExecutorService worker;
    List<dlTask> dlqs = new ArrayList<>();
    String url = "https://freedl.samfrew.com/1e8322f7c8294296/f03e3d5f4c9daf23f90df4cce60a9fd3d3a489c41bfe4c9fbdfb87b9085c76708735fdcb19a7ef01f4cca378be4d9f8ad50fc8a78133cc34eb8b889741de181b28a3b6987a4da321c7710b19868c9dc52263716d37a2934ce46760b0b2d090d144b0261088d92f967157423978f3e6c80de9f8916bbbc57e44696c820dbd569f490e41c2ee2843a8fa23d0f6b3cd92e3/EUX-A155FXXU7DYK1-20251127201751.zip";

    static String fpre = "bin/EUX-A155FXXU7DYK1-20251127201751.zip-part-";
    int pname =0;

    public Limny(){
        //

    }

    public void start(String u){
        //# uncomment line below in production
        //url = u;

        long size =0;
        try {
            size = getSize(url);
        } catch (IOException e) {}

        log("preparing...");
        log("url: "+url);

        log("length: "+size);
        if(size>1024){
            prep500mb(size);
            //prep1KnownSize(size);
        }else{

            prep2UnknownSize();
        }

        //setup worker
        int wp = Math.min(dlqs.size(), PARTS);
        worker= Executors.newFixedThreadPool(wp);

        //
        log("start downloading...");
        log(" ");
        log(" ");

        for(dlTask k : dlqs){
            log(k.savepath +"// "+k.endByte+"//"+k.startByte +(k.endByte-k.startByte));
            //  worker.submit(k);
            //
        }

        proglog();

        // sleep token
        /*
         while(dlqs.isEmpty()==false){
         try {
         proglog();
         Thread.sleep(60000);
         //
         } catch (InterruptedException e) {}
         }
         //*/

    }

    //each task will download 500mb of the file; the last part may download less
    public void prep500mb(long size){
        long s = 0;
        long s2 = MB500;
        while(s2<size){
            pname +=1;
            dlTask k = new dlTask(s, s2);
            dlqs.add(k);

            s = s2+1;
            s2 += MB500;
            //
            if(s2>size){
                s2=size;
                //
                pname+=1;
                k = new dlTask(s, s2);
                dlqs.add(k);
                //
                break;
            }
            //
        }
    }

    public void prep1KnownSize(long size){
        long partSize = size/PARTS;
        long remainder = size % PARTS;
        //
        long s1 = 0;
        long e1 = 0;
        //
        for(int i=0;i<PARTS;i++){
            //
            s1=e1;
            e1 += partSize;

            if(i==PARTS - 1){
                e1 += remainder;
            }
            //
            dlTask k = new dlTask(s1, e1);
            dlqs.add(k);

            e1+=1;

        }
    }

    /*
     split dl into 500mb parts; we do a range test for esch part;
     */
    public void prep2UnknownSize(){
        long curs = 0;
        long mill =  524288000;
        ///
        //

    }

    public long getSize(String u) throws IOException{

        URL url = new URL(u);
        // 1) Obtain content length with a HEAD request
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);
        int responseCode = conn.getResponseCode();
        if (isRedirect(responseCode)) {
            String loc = conn.getHeaderField("Location");
            if (loc != null) {
                URL newUrl = new URL(url, loc);
                System.out.println("Redirected to: " + newUrl);
                conn = (HttpURLConnection) newUrl.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
            }
        }

        long contentLength = -1;
        String cl = conn.getHeaderField("Content-Length");
        if (cl != null) {
            try {
                contentLength = Long.parseLong(cl);
            } catch (NumberFormatException ignored) {
                contentLength = -1;
            }
        }

        return contentLength;
        //
    }
    
    static float toPercent(float score, float total){
        return (score / total ) * 100;
    }

    static float percentOf(float percent, float total){
        return (percent / 100) * total;
    }

    private static boolean isRedirect(int code) {
        return code == HttpURLConnection.HTTP_MOVED_PERM ||
            code == HttpURLConnection.HTTP_MOVED_TEMP ||
            code == HttpURLConnection.HTTP_SEE_OTHER ||
            code == 307 || code == 308;
    }

    public static HttpURLConnection gc(String u) throws IOException{
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        //
        c.setInstanceFollowRedirects(true);
        return c;
    }

    public void log(String s){
        System.out.println(s);
    }

    public void proglog(){
        for(dlTask k : dlqs){
            long t = k.endByte-k.startByte;
            toPercent(k.dlByte, t);
            log(k.savepath+" ("+k.rcode+") ;; ["+t+"]//("+k.dlByte+")//"+"("+t+"%)");
            log("");
        }
    }

    class dlTask implements Runnable{
        long startByte;
        long endByte;
        long dlByte;
        int rcode=0;
        boolean completed;
        String savepath = fpre+"";

        dlTask(long s, long e){
            startByte=s;
            endByte=e;
            //
            savepath+= pname;
        }

        public void run(){
            try {
                //
                //

                HttpURLConnection cc = (HttpURLConnection) new URL(url).openConnection();
                cc.setInstanceFollowRedirects(true);
                cc.setFollowRedirects(true);
                cc.setRequestProperty("User-Agent",  "linux; ubuntu; firefox 301");
                //
                //String cookie = CookieManager.getInstance().getCookie(url);
                // cc.addRequestProperty("cookie", cookie);


                if(endByte>-1){
                    cc.addRequestProperty("Range","bytes="+ startByte +"-"+endByte);
                }else{
                    cc.addRequestProperty("Range","bytes="+ startByte +"-");
                }

                // response
                rcode = cc.getResponseCode();

                RandomAccessFile rf = new RandomAccessFile(savepath,"rw");
                BufferedInputStream bis = null;


                if(rcode<400){
                    //
                    byte[] buff = new byte[1024*1024*3];
                    bis = new BufferedInputStream( cc.getInputStream());
                    int red =0;

                    //while((red= bis.read(buff)) !=-1){
                    while((red = bis.read(buff)) !=-1){
                        //
                        //
                        rf.write(buff, 0, red);
                        dlByte = dlByte + red;

                    }

                    //dlqs.remove(this);


                }else{
                    //notify failure
                    //
                }

                close(rf);
                close(bis);


                dlqs.remove(this);

            } catch (IOException e) {}
        }
    }


    public void close(Closeable c){
        if(c!=null){
            try {
                c.close();
            } catch (IOException e) {}
        }
    }


}
