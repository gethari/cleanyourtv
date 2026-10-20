package com.gathin.tvhome;

import android.content.*;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.*;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Narrow ADB-authorized shell transport; no sockets and no caller-supplied file paths. */
public final class CompanionProvider extends ContentProvider {
    static final String ACTION_UPDATED="com.gathin.tvhome.COMPANION_UPDATED";
    private static CompanionStore instance;
    private static final ThreadPoolExecutor uploads=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4));
    private static final ScheduledExecutorService timeouts=Executors.newSingleThreadScheduledExecutor();

    static synchronized CompanionStore store(Context context) throws Exception {
        Context app=context.getApplicationContext();
        if(instance==null)instance=new CompanionStore(new File(app.getFilesDir(),"companion"),()->{
            File dir=Config.dir(app);JSONObject theme=dir==null?null:Config.read(new File(dir,Config.FILE_NAME));
            return new JSONObject().put("settings",theme==null?new JSONObject():theme)
                .put("preferences",new JSONObject(app.getSharedPreferences(Config.SETTINGS_NAME,Context.MODE_PRIVATE).getAll()));
        },directory->{
            FileDescriptor fd=Os.open(directory.getPath(),OsConstants.O_RDONLY,0);
            try{Os.fsync(fd);}finally{Os.close(fd);}
        },(file,mime)->{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getPath(),options);
            CompanionStore.require(options.outWidth>0 && options.outHeight>0 && options.outWidth<=16384 && options.outHeight<=16384
                && (long)options.outWidth*options.outHeight<=33177600 && mime.equals(options.outMimeType),"invalid_asset");
            BitmapFactory.Options decode=new BitmapFactory.Options();decode.inSampleSize=1;
            while(options.outWidth/decode.inSampleSize>512 || options.outHeight/decode.inSampleSize>512)decode.inSampleSize*=2;
            android.graphics.Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),decode);
            CompanionStore.require(bitmap!=null,"invalid_asset");bitmap.recycle();
        });
        return instance;
    }
    static JSONObject overlay(Context context,JSONObject theme) {
        try{
            JSONObject result=theme==null?new JSONObject():CompanionStore.copy(theme),overrides=store(context).overrides();
            if(overrides.length()==0)return theme;
            for(Iterator<String> it=overrides.keys();it.hasNext();){String key=it.next();result.put(key,overrides.get(key));}
            return result;
        }catch(Exception e){return theme;}
    }
    static List<File> selectedAssets(Context context) {
        try{return store(context).selectedAssets();}catch(Exception e){return Collections.emptyList();}
    }
    static String revision(Context context) {
        try{return store(context).state().getString("revision");}catch(Exception e){return "";}
    }
    static void caller() {
        int uid=Binder.getCallingUid();
        if(uid!=2000 && uid!=Process.myUid())throw new SecurityException("Companion access denied");
    }
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras) {
        caller();String requestId="";JSONObject reply=new JSONObject();
        try{
            CompanionStore.require(arg!=null && arg.length()<=349526 && arg.matches("[A-Za-z0-9_-]+"),"invalid_request");
            byte[] bytes=Base64.decode(arg,Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP);
            CompanionStore.require(bytes.length<=262144,"invalid_request");JSONObject request=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            requestId=request.getString("request_id");CompanionStore.require(CompanionStore.hex(requestId,32),"invalid_request");
            CompanionStore.require(CompanionStore.integer(request.get("protocol_version"),1,1),"unsupported_protocol");
            JSONObject data;CompanionStore store=store(getContext());
            switch(method){
                case "capabilities":data=new JSONObject().put("protocol_version",1).put("launcher_version",BuildConfig.VERSION_NAME)
                    .put("features",new JSONArray(Arrays.asList("collections_v1","atomic_activation_v1","revision_checks_v1","readback_v1")))
                    .put("limits",new JSONObject().put("asset_bytes",CompanionStore.ASSET_BYTES).put("collection_bytes",CompanionStore.TOTAL_BYTES).put("asset_count",CompanionStore.ASSET_COUNT));break;
                case "state":data=store.state();break;
                case "stage":data=store.stage(request);break;
                case "validate":data=store.validate(request.getString("session_id"));break;
                case "activate":data=store.activate(request);getContext().sendBroadcast(new Intent(ACTION_UPDATED).setPackage(getContext().getPackageName()));break;
                case "result":data=store.result(request.getString("operation_id"));break;
                case "discard":data=store.discard(request.getString("session_id"));break;
                default:throw new CompanionStore.Failure("invalid_request");
            }
            reply.put("ok",true).put("data",data);
        }catch(Exception error){
            String code=error instanceof CompanionStore.Failure?((CompanionStore.Failure)error).code:
                error instanceof JSONException || error instanceof IllegalArgumentException?"invalid_request":"internal_error";
            try{reply.put("ok",false).put("error",new JSONObject().put("code",code).put("message","The launcher could not complete this operation."));}catch(JSONException ignored){}
        }
        try{reply.put("protocol_version",1).put("request_id",requestId);}catch(JSONException ignored){}
        Bundle result=new Bundle();result.putString("reply",Base64.encodeToString(reply.toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_PADDING|Base64.NO_WRAP));return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        caller();final ParcelFileDescriptor[] pipe;
        try{
            List<String> parts=uri.getPathSegments();CompanionStore.require("w".equals(mode) && parts.size()==5 && "v1".equals(parts.get(0)) && "sessions".equals(parts.get(1)) && "assets".equals(parts.get(3)),"invalid_request");
            String session=parts.get(2),sha=parts.get(4);CompanionStore.require(CompanionStore.hex(session,32) && CompanionStore.hex(sha,64),"invalid_request");
            CompanionStore store=store(getContext());store.session(session);pipe=ParcelFileDescriptor.createReliablePipe();
            try{uploads.execute(()->{
                ScheduledFuture<?> timeout=timeouts.schedule(()->{try{pipe[0].closeWithError("Transfer timed out");}catch(IOException ignored){}},60,TimeUnit.SECONDS);
                try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){store.receive(session,sha,in);}
                catch(Exception failure){try{pipe[0].closeWithError("Asset rejected");}catch(IOException ignored){}}
                finally{timeout.cancel(false);}
            });}catch(RejectedExecutionException e){pipe[0].close();pipe[1].close();throw e;}
            return pipe[1];
        }catch(Exception e){throw new FileNotFoundException("Companion asset transfer unavailable");}
    }
    @Override public String getType(Uri uri){caller();return "application/octet-stream";}
    @Override public Cursor query(Uri uri,String[] p,String s,String[] a,String o){caller();throw new UnsupportedOperationException();}
    @Override public Uri insert(Uri uri,ContentValues v){caller();throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues v,String s,String[] a){caller();throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String s,String[] a){caller();throw new UnsupportedOperationException();}
}
