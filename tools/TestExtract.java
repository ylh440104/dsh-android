import com.deepseek.harness.Tar;
import org.tukaani.xz.XZInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class TestExtract {
    public static void main(String[] args) throws Exception {
        File archive = new File(args[0]);
        File dest = new File(args[1]);
        if (dest.exists()) deleteRec(dest);
        dest.mkdirs();
        InputStream in = new XZInputStream(new BufferedInputStream(new FileInputStream(archive), 65536));
        try {
            Tar.extract(in, dest);
        } finally {
            in.close();
        }
        System.out.println("extracted to " + dest.getAbsolutePath());
    }

    private static void deleteRec(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRec(x);
        }
        f.delete();
    }
}
