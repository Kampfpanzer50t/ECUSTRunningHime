package cn.huati.assistant;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

final class SecureStore {
    private static final String ALIAS="huati.local.v1";
    private final Context context;
    SecureStore(Context context) {this.context=context.getApplicationContext();}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)) {
            KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    private AtomicFile file(String name) {
        if(!name.matches("[a-z_]+")) throw new IllegalArgumentException("文件名无效");
        return new AtomicFile(new File(context.getFilesDir(),name+".secure"));
    }
    synchronized JSONObject read(String name) throws Exception {
        AtomicFile f=file(name);if(!f.getBaseFile().exists()) return null;
        byte[] data=f.readFully(); if(data.length<29) throw new IllegalStateException("本地配置无法读取，请重新导入会话");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(data,12)));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(Arrays.copyOfRange(data,12,data.length)),StandardCharsets.UTF_8));
    }
    synchronized void write(String name,JSONObject value) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        AtomicFile f=file(name);FileOutputStream out=f.startWrite();
        try {out.write(cipher.getIV());out.write(encrypted);f.finishWrite(out);}
        catch(Exception e){f.failWrite(out);throw e;}
    }
    synchronized void remove(String name) {file(name).delete();}
}
