package com.gathin.tvhome;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class CompanionStoreTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private static final String SESSION="0123456789abcdef0123456789abcdef",OP="fedcba9876543210fedcba9876543210";
    private JSONObject source;
    private File root;
    private CompanionStore store;
    private AtomicBoolean failRootSync;
    @Before public void setup() throws Exception {
        root=temporary.newFolder();source=new JSONObject("{\"settings\":{\"wallpaper\":true,\"future_key\":{\"nested\":1},\"provider_token\":\"fixture\"},\"preferences\":{\"columns\":5}}");
        failRootSync=new AtomicBoolean(false);store=make();
    }
    private CompanionStore make() throws Exception {
        return new CompanionStore(root,()->source,dir->{
            if(dir.equals(root) && failRootSync.getAndSet(false))throw new IOException("simulated crash after atomic replacement");
            try(FileChannel channel=FileChannel.open(dir.toPath(),StandardOpenOption.READ)){channel.force(true);}
        },(file,mime)->{});
    }
    private JSONObject request(String patch,JSONArray collections) throws Exception {
        return new JSONObject().put("protocol_version",1).put("request_id",SESSION).put("expected_revision",store.state().getString("revision"))
            .put("patch",new JSONObject(patch)).put("collections",collections);
    }
    private JSONObject activation(String revision) throws Exception {
        return new JSONObject().put("request_id",OP).put("session_id",SESSION).put("expected_revision",revision);
    }
    private void failure(String code,Throwing work) throws Exception {
        try{work.run();fail("Expected "+code);}catch(CompanionStore.Failure failure){assertEquals(code,failure.code);}
    }
    interface Throwing{void run() throws Exception;}
    @Test public void activation_is_idempotent_and_readback_excludes_private_unknown_settings() throws Exception {
        JSONObject before=store.state();JSONObject stage=request("{\"set\":{\"wallpaper\":false}}",new JSONArray());store.stage(stage);
        assertTrue(store.state().getJSONObject("settings").getBoolean("wallpaper"));
        assertTrue(store.validate(SESSION).getBoolean("ready"));
        JSONObject result=store.activate(activation(before.getString("revision")));
        assertFalse(store.state().getJSONObject("settings").getBoolean("wallpaper"));
        assertEquals(result.getString("revision"),store.state().getString("revision"));
        assertEquals(result.toString(),store.activate(activation(before.getString("revision"))).toString());
        assertFalse(store.state().toString().contains("provider_token"));assertFalse(store.state().toString().contains("fixture"));
        assertEquals(1,source.getJSONObject("settings").getJSONObject("future_key").getInt("nested"));
        failure("request_conflict",()->store.discard(SESSION));
        assertEquals("activated",make().result(OP).getString("status"));
    }
    @Test public void operation_identifier_cannot_activate_a_different_session() throws Exception {
        JSONObject first=request("{}",new JSONArray());store.stage(first);store.activate(activation(first.getString("expected_revision")));
        String second="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        JSONObject next=request("{}",new JSONArray()).put("request_id",second);store.stage(next);
        JSONObject reused=activation(next.getString("expected_revision")).put("session_id",second);
        failure("request_conflict",()->store.activate(reused));
    }
    @Test public void later_manual_theme_or_tv_preference_changes_block_activation() throws Exception {
        JSONObject stage=request("{\"set\":{\"wallpaper\":false}}",new JSONArray());store.stage(stage);
        source.getJSONObject("preferences").put("columns",6);
        failure("revision_conflict",()->store.activate(activation(stage.getString("expected_revision"))));
        assertTrue(store.state().getJSONObject("settings").getBoolean("wallpaper"));
    }
    @Test public void unknown_keys_conflicting_patch_and_traversal_are_rejected() throws Exception {
        failure("unsupported_feature",()->store.stage(request("{\"set\":{\"provider_token\":\"bad\"}}",new JSONArray())));
        failure("invalid_request",()->store.stage(request("{\"set\":{\"wallpaper\":false},\"remove\":[\"wallpaper\"]}",new JSONArray())));
        failure("invalid_request",()->store.discard("../../outside"));
    }
    @Test public void discard_is_idempotent_and_session_identifiers_cannot_be_reused() throws Exception {
        JSONObject stage=request("{}",new JSONArray());store.stage(stage);store.discard(SESSION);store.discard(SESSION);
        failure("request_conflict",()->store.stage(stage));failure("unknown_session",()->store.validate(SESSION));
    }
    @Test public void lost_ack_after_atomic_replacement_reconciles_after_restart() throws Exception {
        JSONObject stage=request("{\"set\":{\"wallpaper_dim\":25}}",new JSONArray());store.stage(stage);failRootSync.set(true);
        try{store.activate(activation(stage.getString("expected_revision")));fail();}catch(IOException expected){}
        CompanionStore reopened=make();assertEquals(25,reopened.state().getJSONObject("settings").getInt("wallpaper_dim"));
        assertEquals("activated",reopened.result(OP).getString("status"));
        reopened.activate(activation(stage.getString("expected_revision")));
        assertEquals(1,reopened.active().getJSONObject("operations").length());
    }
    @Test public void truncated_oversized_and_hash_mismatched_uploads_do_not_activate() throws Exception {
        byte[] bytes={1,2,3,4};String sha=CompanionStore.hash(bytes);
        JSONArray collections=new JSONArray().put(new JSONObject().put("id",OP).put("order","shuffle").put("interval_minutes",15)
            .put("assets",new JSONArray().put(new JSONObject().put("sha256",sha).put("bytes",4).put("mime","image/png"))));
        store.stage(request("{}",collections));
        failure("invalid_asset",()->store.receive(SESSION,sha,new ByteArrayInputStream(new byte[]{1,2})));
        failure("invalid_asset",()->store.receive(SESSION,sha,new ByteArrayInputStream(new byte[]{1,2,3,4,5})));
        failure("invalid_asset",()->store.receive(SESSION,sha,new ByteArrayInputStream(new byte[]{4,3,2,1})));
        assertFalse(store.validate(SESSION).getBoolean("ready"));
        store.receive(SESSION,sha,new ByteArrayInputStream(bytes));assertTrue(store.validate(SESSION).getBoolean("ready"));
        assertEquals(1,store.assets.list().length);
    }
}
