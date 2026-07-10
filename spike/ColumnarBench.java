import gnu.trove.list.array.TDoubleArrayList;

/**
 * Ceiling test: extend 40k objects' per-variant storage from 1 -> 51 variants, 50 times,
 * the way cloneVariant does. Compares the current per-object TDoubleArrayList layout (AoS)
 * against a columnar double[] layout (SoA) cloned with System.arraycopy.
 */
public class ColumnarBench {
    static final int N = 40000;   // ~terminals in PEGASE
    static final int CLONES = 50;
    static final int FIELDS = 2;  // p, q

    // ---- current layout: per-object trove arrays ----
    static long aos() {
        TDoubleArrayList[] p = new TDoubleArrayList[N];
        TDoubleArrayList[] q = new TDoubleArrayList[N];
        for (int i = 0; i < N; i++) {
            p[i] = new TDoubleArrayList(1); p[i].add(Double.NaN);
            q[i] = new TDoubleArrayList(1); q[i].add(Double.NaN);
        }
        long t0 = System.nanoTime();
        for (int c = 0; c < CLONES; c++) {
            int src = 0;
            for (int i = 0; i < N; i++) {          // VariantManager loops all objects
                p[i].add(p[i].get(src));            // extendVariantArraySize (number=1)
                q[i].add(q[i].get(src));
            }
        }
        long t = System.nanoTime() - t0;
        double sink = 0; for (int i=0;i<N;i++) sink += p[i].size();
        if (sink < 0) System.out.println(sink);
        return t;
    }

    // ---- columnar layout: one array per field, variant-major so a variant is contiguous ----
    static long soa() {
        int cap = 1;
        double[] p = new double[N * (CLONES + 1)];  // [variant*N + obj]
        double[] q = new double[N * (CLONES + 1)];
        java.util.Arrays.fill(p, 0, N, Double.NaN);
        java.util.Arrays.fill(q, 0, N, Double.NaN);
        int size = 1; // variants
        long t0 = System.nanoTime();
        for (int c = 0; c < CLONES; c++) {
            int src = 0;
            System.arraycopy(p, src * N, p, size * N, N);  // clone whole column
            System.arraycopy(q, src * N, q, size * N, N);
            size++;
        }
        long t = System.nanoTime() - t0;
        double sink = p[0] + q[(size-1)*N]; if (sink==12345.6) System.out.println(sink);
        return t;
    }

    public static void main(String[] a) {
        for (int w = 0; w < 5; w++) { aos(); soa(); }
        long[] aosT = new long[9], soaT = new long[9];
        for (int r = 0; r < 9; r++) { aosT[r] = aos(); soaT[r] = soa(); }
        java.util.Arrays.sort(aosT); java.util.Arrays.sort(soaT);
        System.out.printf("AoS (current per-object trove): median %.2f ms%n", aosT[4]/1e6);
        System.out.printf("SoA (columnar arraycopy):       median %.2f ms%n", soaT[4]/1e6);
        System.out.printf("speedup: %.1fx%n", (double)aosT[4]/soaT[4]);
    }
}
