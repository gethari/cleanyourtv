package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Pure transaction store; Android transport/caller checks live in CompanionProvider. */
final class CompanionStore {
    static final long ASSET_BYTES = 20L * 1024 * 1024, TOTAL_BYTES = 256L * 1024 * 1024;
    static final int ASSET_COUNT = 200;
    static final Set<String> KEYS = new HashSet<>(Arrays.asList("wallpaper", "wallpaper_rotation_minutes",
        "wallpaper_dim", "wallpaper_order", "active_collection", "background_color", "accent_color", "reduced_motion"));
    interface Source { JSONObject read() throws Exception; }
    interface Sync { void directory(File directory) throws Exception; }
    interface ImageCheck { void verify(File image, String mime) throws Exception; }
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    final File root, sessions, assets;
    private final Source source;
    private final Sync sync;
    private final ImageCheck images;
    private final Set<String> writers = new HashSet<>();

    CompanionStore(File root, Source source, Sync sync, ImageCheck images) throws Exception {
        this.root = root; this.source = source; this.sync = sync; this.images = images;
        sessions = new File(root, "sessions"); assets = new File(root, "assets");
        if ((!sessions.isDirectory() && !sessions.mkdirs()) || (!assets.isDirectory() && !assets.mkdirs())) throw new IOException("storage");
    }
    static boolean hex(String value, int length) { return value != null && value.matches("[a-f0-9]{" + length + "}"); }
    static void require(boolean valid, String error) throws Failure { if (!valid) throw new Failure(error); }
    static String canonical(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); Collections.sort(keys);
            List<String> values = new ArrayList<>();
            for (String key : keys) values.add(JSONObject.quote(key) + ":" + canonical(object.get(key)));
            return "{" + String.join(",", values) + "}";
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; List<String> values = new ArrayList<>();
            for (int i=0; i<array.length(); i++) values.add(canonical(array.get(i)));
            return "[" + String.join(",", values) + "]";
        }
        if(value==null || value==JSONObject.NULL)return "null";
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Number)return JSONObject.numberToString((Number)value);
        return value.toString();
    }
    static String hash(byte[] data) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(data); StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536];
        try (InputStream in = new FileInputStream(file)) { int read; while ((read=in.read(bytes))!=-1) digest.update(bytes,0,read); }
        StringBuilder text = new StringBuilder(); for (byte b : digest.digest()) text.append(String.format(Locale.ROOT,"%02x",b&255)); return text.toString();
    }
    static JSONObject copy(JSONObject object) throws Exception { return new JSONObject(object.toString()); }
    JSONObject read(File file, JSONObject fallback) throws Exception {
        if (!file.exists()) return copy(fallback);
        require(file.length()<=262144, "invalid_request");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }
    void atomic(File file, JSONObject value) throws Exception {
        byte[] bytes = canonical(value).getBytes(StandardCharsets.UTF_8); require(bytes.length<=262144,"storage_full");
        File temp = new File(file.getParentFile(), file.getName()+".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        sync.directory(file.getParentFile());
    }
    JSONObject active() throws Exception {
        return read(new File(root,"active.json"),new JSONObject().put("settings",new JSONObject()).put("collections",new JSONArray()).put("operations",new JSONObject()));
    }
    String revision(JSONObject active, JSONObject legacy) throws Exception {
        return hash(canonical(new JSONObject().put("settings",active.getJSONObject("settings"))
            .put("collections",active.getJSONArray("collections")).put("source",legacy)).getBytes(StandardCharsets.UTF_8));
    }
    static JSONObject safe(JSONObject object) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : KEYS) if (object.has(key)) result.put(key,object.get(key));
        return result;
    }
    synchronized JSONObject state() throws Exception {
        JSONObject active = active(), legacy = source.read(), effective = copy(legacy.optJSONObject("settings") == null ? new JSONObject() : legacy.getJSONObject("settings"));
        JSONObject overrides = active.getJSONObject("settings");
        for (Iterator<String> it=overrides.keys();it.hasNext();) { String key=it.next(); effective.put(key,overrides.get(key)); }
        return new JSONObject().put("revision",revision(active,legacy)).put("settings",safe(effective)).put("overrides",safe(overrides))
            .put("collections",active.getJSONArray("collections")).put("active_collection",effective.opt("active_collection"))
            .put("operation",active.optJSONObject("last_operation"));
    }
    static boolean integer(Object value, long min, long max) {
        if (!(value instanceof Number)) return false;
        double number=((Number)value).doubleValue(); return number>=min && number<=max && number==Math.floor(number);
    }
    static void patch(JSONObject patch) throws Exception {
        for (Iterator<String> it=patch.keys();it.hasNext();) require(Arrays.asList("set","remove").contains(it.next()),"invalid_request");
        JSONObject set=patch.optJSONObject("set"); JSONArray remove=patch.optJSONArray("remove");
        require(!patch.has("set") || set!=null,"invalid_request"); require(!patch.has("remove") || remove!=null,"invalid_request");
        if(set==null)set=new JSONObject(); if(remove==null)remove=new JSONArray();
        Set<String> removed=new HashSet<>();
        for(int i=0;i<remove.length();i++) {
            String key=remove.getString(i); require(KEYS.contains(key) && !set.has(key) && removed.add(key),"invalid_request");
        }
        for(Iterator<String> it=set.keys();it.hasNext();) {
            String key=it.next(); require(KEYS.contains(key),"unsupported_feature"); Object value=set.get(key); boolean valid;
            switch(key) {
                case "wallpaper": case "reduced_motion": valid=value instanceof Boolean;break;
                case "wallpaper_rotation_minutes": valid=integer(value,1,1440);break;
                case "wallpaper_dim": valid=integer(value,0,100);break;
                case "wallpaper_order":valid=value instanceof String && Arrays.asList("sequential","shuffle").contains(value);break;
                case "active_collection":valid=value instanceof String && hex((String)value,32);break;
                default:valid=value instanceof String && ((String)value).matches("#[a-fA-F0-9]{6}");
            }
            require(valid,"invalid_request");
        }
    }
    private File sessionFile(String id) throws Exception { require(hex(id,32),"invalid_request"); return new File(sessions,id+".json"); }
    JSONObject session(String id) throws Exception { File file=sessionFile(id); require(file.isFile(),"unknown_session"); JSONObject value=read(file,new JSONObject());require(!value.optBoolean("discarded",false),"unknown_session");return value; }
    private JSONObject manifestAsset(JSONObject session,String sha) throws Exception {
        require(hex(sha,64),"invalid_request"); JSONArray collections=session.getJSONArray("collections");
        for(int i=0;i<collections.length();i++) {
            JSONArray list=collections.getJSONObject(i).getJSONArray("assets");
            for(int j=0;j<list.length();j++) if(sha.equals(list.getJSONObject(j).getString("sha256"))) return list.getJSONObject(j);
        }
        throw new Failure("invalid_asset");
    }
    private void manifest(JSONArray collections) throws Exception {
        require(collections.length()<=20,"invalid_request"); long total=0; int count=0; Set<String> ids=new HashSet<>();
        for(int i=0;i<collections.length();i++) {
            JSONObject collection=collections.getJSONObject(i);for(Iterator<String> key=collection.keys();key.hasNext();)require(Arrays.asList("id","assets","order","interval_minutes").contains(key.next()),"invalid_request"); String id=collection.getString("id"); require(hex(id,32) && ids.add(id),"invalid_request");
            require(Arrays.asList("sequential","shuffle").contains(collection.getString("order")),"invalid_request");
            require(integer(collection.get("interval_minutes"),1,1440),"invalid_request");
            JSONArray list=collection.getJSONArray("assets"); require(list.length()>0,"invalid_request"); Set<String> hashes=new HashSet<>();
            for(int j=0;j<list.length();j++) {
                JSONObject asset=list.getJSONObject(j);for(Iterator<String> key=asset.keys();key.hasNext();)require(Arrays.asList("sha256","bytes","mime").contains(key.next()),"invalid_request"); String sha=asset.getString("sha256");
                require(hex(sha,64) && hashes.add(sha) && integer(asset.get("bytes"),1,ASSET_BYTES),"invalid_asset");
                require(Arrays.asList("image/jpeg","image/png","image/webp").contains(asset.getString("mime")),"invalid_asset");
                total+=asset.getLong("bytes"); count++;
            }
        }
        require(total<=TOTAL_BYTES && count<=ASSET_COUNT,"storage_full");
        require(root.getUsableSpace()>total+1024*1024,"storage_full");
    }
    synchronized JSONObject stage(JSONObject request) throws Exception {
        String id=request.getString("request_id"); File file=sessionFile(id);
        if(file.exists()) { JSONObject old=read(file,new JSONObject());require(!old.optBoolean("discarded",false),"request_conflict"); require(canonical(old.getJSONObject("request")).equals(canonical(request)),"request_conflict"); return new JSONObject().put("session_id",id); }
        require(request.getString("expected_revision").equals(state().getString("revision")),"revision_conflict");
        patch(request.getJSONObject("patch")); JSONArray collections=request.getJSONArray("collections"); manifest(collections);
        JSONObject session=new JSONObject().put("request",copy(request)).put("expected_revision",request.getString("expected_revision"))
            .put("patch",request.getJSONObject("patch")).put("collections",collections).put("created",System.currentTimeMillis());
        atomic(file,session); return new JSONObject().put("session_id",id);
    }
    void receive(String id,String sha,InputStream in) throws Exception {
        JSONObject spec; String token=id+":"+sha;
        synchronized(this) { spec=manifestAsset(session(id),sha); require(writers.add(token),"busy"); }
        File temp=new File(assets,sha+"."+id+".part");
        try {
            long total=0; byte[] bytes=new byte[65536];
            try(FileOutputStream out=new FileOutputStream(temp)) {
                int n; while((n=in.read(bytes))!=-1) {total+=n;require(total<=spec.getLong("bytes") && total<=ASSET_BYTES,"invalid_asset");out.write(bytes,0,n);}
                require(total==spec.getLong("bytes"),"invalid_asset");out.getFD().sync();
            }
            require(hash(temp).equals(sha),"invalid_asset"); images.verify(temp,spec.getString("mime"));
            synchronized(this) {
                session(id); Files.move(temp.toPath(),new File(assets,sha).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);sync.directory(assets);
            }
        } finally { Files.deleteIfExists(temp.toPath()); synchronized(this){writers.remove(token);} }
    }
    synchronized JSONObject validate(String id) throws Exception {
        JSONObject session=session(id); require(session.getString("expected_revision").equals(state().getString("revision")),"revision_conflict");
        JSONArray collections=session.getJSONArray("collections");manifest(collections);JSONArray missing=new JSONArray();
        for(int i=0;i<collections.length();i++) {
            JSONArray list=collections.getJSONObject(i).getJSONArray("assets");
            for(int j=0;j<list.length();j++) {
                JSONObject spec=list.getJSONObject(j);String sha=spec.getString("sha256");File file=new File(assets,sha);
                if(!file.isFile() || file.length()!=spec.getLong("bytes") || !hash(file).equals(sha)) missing.put(sha);
            }
        }
        return new JSONObject().put("ready",missing.length()==0).put("missing_assets",missing).put("revision",state().getString("revision"));
    }
    synchronized JSONObject activate(JSONObject request) throws Exception {
        String id=request.getString("session_id"), op=request.getString("request_id");require(hex(op,32),"invalid_request");
        JSONObject active=active(), operations=active.getJSONObject("operations");
        for(Iterator<String> it=operations.keys();it.hasNext();) {String previous=it.next();JSONObject result=operations.getJSONObject(previous);if(id.equals(result.getString("session_id")))return new JSONObject().put("revision",result.getString("revision")).put("operation_id",previous);}
        require(request.getString("expected_revision").equals(state().getString("revision")),"revision_conflict");
        JSONObject checked=validate(id);require(checked.getBoolean("ready"),"missing_assets");JSONObject session=session(id), patch=session.getJSONObject("patch");
        JSONObject values=active.getJSONObject("settings"),set=patch.optJSONObject("set");JSONArray remove=patch.optJSONArray("remove");
        if(remove!=null)for(int i=0;i<remove.length();i++)values.remove(remove.getString(i));
        if(set!=null)for(Iterator<String> it=set.keys();it.hasNext();) {String key=it.next();values.put(key,set.get(key));}
        JSONArray merged=active.getJSONArray("collections"),incoming=session.getJSONArray("collections");
        for(int i=0;i<incoming.length();i++) {JSONObject collection=incoming.getJSONObject(i);int found=-1;for(int j=0;j<merged.length();j++)if(merged.getJSONObject(j).getString("id").equals(collection.getString("id")))found=j;if(found<0)merged.put(collection);else merged.put(found,collection);}
        String selected=values.optString("active_collection","");if(!selected.isEmpty()) {boolean exists=false;for(int i=0;i<merged.length();i++)exists|=merged.getJSONObject(i).getString("id").equals(selected);require(exists,"invalid_request");}
        active.put("collections",merged);String revision=revision(active,source.read());
        require(revision(active(),source.read()).equals(request.getString("expected_revision")),"revision_conflict");
        require(operations.length()<1000,"storage_full");JSONObject result=new JSONObject().put("session_id",id).put("revision",revision);
        operations.put(op,result);active.put("last_operation",new JSONObject().put("request_id",op).put("session_id",id).put("revision",revision));
        atomic(new File(root,"active.json"),active);
        return new JSONObject().put("revision",revision).put("operation_id",op);
    }
    synchronized JSONObject result(String operation) throws Exception {
        require(hex(operation,32),"invalid_request");JSONObject found=active().getJSONObject("operations").optJSONObject(operation);
        return found==null?new JSONObject().put("status","unknown"):copy(found).put("status","activated");
    }
    synchronized JSONObject discard(String id) throws Exception {
        for(Iterator<String> it=writers.iterator();it.hasNext();)require(!it.next().startsWith(id+":"),"busy");
        JSONObject operations=active().getJSONObject("operations");for(Iterator<String> it=operations.keys();it.hasNext();)require(!id.equals(operations.getJSONObject(it.next()).getString("session_id")),"request_conflict");
        File file=sessionFile(id);if(file.isFile()){JSONObject value=read(file,new JSONObject());value.put("discarded",true);atomic(file,value);}return new JSONObject().put("status","discarded");
    }
    synchronized JSONObject overrides() throws Exception {return copy(active().getJSONObject("settings"));}
    synchronized List<File> selectedAssets() throws Exception {
        JSONObject active=active();String selected=active.getJSONObject("settings").optString("active_collection","");List<File> result=new ArrayList<>();
        JSONArray collections=active.getJSONArray("collections");for(int i=0;i<collections.length();i++) {JSONObject c=collections.getJSONObject(i);if(!selected.equals(c.getString("id")))continue;JSONArray list=c.getJSONArray("assets");for(int j=0;j<list.length();j++){String sha=list.getJSONObject(j).getString("sha256");if(hex(sha,64)){File f=new File(assets,sha);if(f.isFile())result.add(f);}}}
        return result;
    }
}
