package bench;

import com.powsybl.iidm.network.Network;

import java.nio.file.Path;
import java.util.Properties;

/**
 * Read a matpower .mat case and export it as a CGMES zip, for scaling benchmarks.
 * Usage: CgmesGen <in.mat> <out-basename-without-ext>
 */
public final class CgmesGen {
    private CgmesGen() {
    }

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]);
        Path outBase = Path.of(args[1]);
        long t0 = System.currentTimeMillis();
        Network n = Network.read(in);
        System.out.printf("read %s: %d buses in %d ms%n", in.getFileName(),
                n.getBusView().getBusStream().count(), System.currentTimeMillis() - t0);
        Properties p = new Properties();
        t0 = System.currentTimeMillis();
        n.write("CGMES", p, outBase);
        System.out.printf("wrote CGMES %s in %d ms%n", outBase, System.currentTimeMillis() - t0);
    }
}
