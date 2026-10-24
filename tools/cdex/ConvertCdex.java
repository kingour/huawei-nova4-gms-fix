import java.nio.file.Files;
import java.nio.file.Path;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.CDexBackedDexFile;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

public class ConvertCdex {
    public static void main(String[] args) throws Exception {
        var dex = new CDexBackedDexFile(Opcodes.forApi(29), Files.readAllBytes(Path.of(args[0])));
        DexPool.writeTo(args[1], dex);
        System.out.println("Converted classes: " + dex.getClasses().size());
    }
}
