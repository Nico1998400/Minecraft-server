import java.nio.file.Path;

public final class ProbeCarpet {
    public static void main(String[] args) {
        WorldReader w = new WorldReader(Path.of("c:/Users/nico1/sebstah/minecraft-server/swedencore/run/world/dimensions/minecraft/overworld/region"));
        int y = 126;
        System.out.println("x from 1636 to 1672 (left to right), z from 890 to 916 (top to bottom); c=red carpet, #=blocked, .=free floor, _=no floor");
        System.out.print("       ");
        for (int x = 1636; x <= 1672; x++) System.out.print(x % 10 == 0 ? (x / 10) % 10 : " ");
        System.out.println();
        for (int z = 890; z <= 916; z++) {
            StringBuilder sb = new StringBuilder(String.format("z=%d  ", z));
            for (int x = 1636; x <= 1672; x++) {
                String feet = w.block(x, y, z);
                String head = w.block(x, y + 1, z);
                String floor = w.block(x, y - 1, z);
                char c;
                if (feet != null && feet.contains("carpet")) c = 'c';
                else if (!(feet == null || feet.contains("air")) || !(head == null || head.contains("air"))) c = '#';
                else if (floor == null || floor.contains("air")) c = '_';
                else c = '.';
                sb.append(c);
            }
            System.out.println(sb);
        }
    }
}
