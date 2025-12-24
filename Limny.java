
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

    public static void main(String[] args) {
        new Limny().start(args[0]);
    }

    ExecutorService worker;
    List<dlTask> dlqs = new ArrayList<>();
    String url = "https://freedl.samfrew.com/1e8322f7c8294296/61084feb9490d07e3d96109746cf8c444f4b43f373d6087f916a5b2bc1f089bad7f616d0b33b871ac7b9ace4caad9c86ecde39a6b320d21a5a244fa89bbf7c492ea7f150c91605b4c8822b123b1dfa0e43cef194e1f2fe4517bba002ad5f15cf2d552aaa82dd8a349cdc4cf4388af49153803e6cfb3c1db0c4a4a1769b71f5034edbf7d3cbd97db6e3e2ee58a280da35/EUX-A155FXXU7DYK1-20251127201751.zip";

    static String fpre = "bin/EUX-A155FXXU7DYK1-20251127201751.zip-part-";
    int pname =0;

    long mil = 1000000;
    long sbyte=0;

    public Limny(){
        //
    }

    public void start(String u){
        //# uncomment line below in production
        //url = u;
        worker= Executors.newFixedThreadPool(7);


        log("downloading...");
        log(url);
        log(" ");

        //
        while(sbyte<7000000){
            long b1 = sbyte;
            sbyte += mil;
            long b2 = sbyte;
            sbyte+=1;
            pname+=1;
            dlqs.add(new dlTask(b1, b2));
            //
        }

        pname+=1;
        dlqs.add(new dlTask(sbyte,-1));

        for(dlTask k : dlqs){
            log(k.savepath+"-->"+k.startByte+"~"+k.endByte);
            log("");
            // strt the download
            worker.submit(k);
            //
        }





        // sleep token
        while(dlqs.isEmpty()==false){
            try {
                proglog();
                Thread.sleep(6000);
                
            } catch (InterruptedException e) {}
        }

    }

    public void log(String s){
        System.out.println(s);
    }

    public void proglog(){
        for(dlTask k : dlqs){
            log(k.savepath+"");
            log(k.endByte+"//"+k.dlByte+"//");
            log("");
        }
    }

    class dlTask implements Runnable{
        long startByte;
        long endByte;
        long dlByte=0;
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
                cc.setRequestProperty("User-Agent",  "windows 10 pro;chrome 212");
                //
                //String cookie = CookieManager.getInstance().getCookie(url);
                cc.setFollowRedirects(true);
                // cc.addRequestProperty("cookie", cookie);
                if(endByte>-1){
                    cc.addRequestProperty("Range","bytes="+ startByte +"-"+endByte);
                }else{
                    cc.addRequestProperty("Range","bytes="+ startByte +"-");
                }

                // response
                int rc = cc.getResponseCode();

                RandomAccessFile rf = new RandomAccessFile(savepath,"rw");
                BufferedInputStream bis = null;


                if(rc<400){
                    //
                    byte[] buff = new byte[1024*1024*3];
                    bis = new BufferedInputStream( cc.getInputStream());
                    int red =0;

                    //while((red= bis.read(buff)) !=-1){
                    while((red= bis.read(buff)) !=-1){
                        //
                        //
                        rf.write(buff, 0, red);
                        dlByte += red;

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
