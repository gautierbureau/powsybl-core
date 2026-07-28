package bench;

import com.powsybl.commons.datasource.DirectoryDataSource;
import com.powsybl.iidm.network.Network;

import java.nio.file.Path;

/** Import a CGMES model once, with -Dcgmes.query.instrument=true, to size query costs. */
public final class CgmesProfile {
    private CgmesProfile() {
    }

    public static void main(String[] args) throws Exception {
        Path cgmesDir = Path.of(args[0]);
        String baseName = args[1];
        long t0 = System.nanoTime();
        Network n = Network.read(new DirectoryDataSource(cgmesDir, baseName));
        long t1 = System.nanoTime();
        System.out.println("cgmes-import wall: " + (t1 - t0) / 1_000_000 + " ms, network=" + n.getId());
    }
}
