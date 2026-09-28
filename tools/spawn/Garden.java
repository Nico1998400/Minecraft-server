import java.util.Random;

/**
 * Turns a lot into a property: sets the villa back from the street, eases the ground towards the house (terraces and
 * retaining walls on slopes), and gives every garden its own character: boundary, gate and path, driveway, mailbox
 * with the address, flower beds, trees, a patio with furniture, and extras by size and taste.
 */
final class Garden {

    static final Random R = VillaDistrict.R;

    enum Character { FORMAL, NATURAL, URBAN, PRIVATE }

    static VillaDistrict.Lot build(VillaDistrict.Lot lot, Villa.Family fam) {
        B.Frame lf = lot.frame();
        int w = lot.w(), d = lot.d();
        int[] hs = Villa.size(fam, R);
        int hw = Math.min(hs[0], w - 8), hd = Math.min(hs[1], d - 11);
        int setback = switch (lot.tier()) {
            case "SMALL" -> 4;
            case "MEDIUM" -> 5 + R.nextInt(2);
            case "LARGE" -> 6 + R.nextInt(2);
            default -> 7 + R.nextInt(3);
        };
        setback = Math.max(4, Math.min(setback, d - hd - 5));
        boolean garageLeft = R.nextBoolean();
        int hx = garageLeft ? w - hw - 3 - R.nextInt(2) : 3 + R.nextInt(2);
        if (fam == Villa.Family.LUXURY || fam == Villa.Family.MODERN) hx = Math.max(7, Math.min(w - hw - 4, (w - hw) / 2 + 3));
        int hz = d - setback - hd;
        // floor level: just above the highest ground under the house
        int floor = Integer.MIN_VALUE;
        for (int x = hx; x < hx + hw; x++) for (int z = hz; z < hz + hd; z++) floor = Math.max(floor, g(lf, x, z));
        floor += 1;
        int front = pavement(lf, w, d);
        grade(lf, w, d, hx, hz, hw, hd, floor, front);
        B.Frame hf = new B.Frame(lf.wx(hx, hz), floor, lf.wz(hx, hz), lf.turns());
        // foundation under the house down to the ground
        for (int x = -1; x <= hw; x++) for (int z = -1; z <= hd; z++) {
            int gy = g(lf, hx + x, hz + z);
            for (int y = gy - floor; y < 0; y++) hf.set(x, y, z, "stone_bricks");
        }
        Villa.Info info = Villa.build(fam, hf, hw, hd, R);
        Character ch = switch (lot.tier()) {
            case "PREMIUM" -> R.nextBoolean() ? Character.PRIVATE : Character.FORMAL;
            case "LARGE" -> Character.values()[R.nextInt(4)];
            case "MEDIUM" -> R.nextBoolean() ? Character.FORMAL : Character.NATURAL;
            default -> R.nextBoolean() ? Character.URBAN : Character.NATURAL;
        };
        int doorX = hx + info.doorX, doorZ = hz + info.doorZ;
        int driveX = info.garageX >= 0 ? hx + info.garageX : garageLeft ? 1 : w - 3;
        driveX = Math.max(1, Math.min(w - 3, driveX - 1));
        boundary(lf, w, d, ch, doorX, driveX);
        path(lf, doorX, doorZ, d, ch);
        driveway(lf, driveX, d, hz + hd + (info.garageX >= 0 ? 0 : -2), ch);
        mailbox(lf, doorX, d, lot);
        beds(lf, hx, hx + hw - 1, hz + hd, ch, doorX);
        backyard(lf, w, hx, hz, hw, floor, ch, lot);
        trees(lf, w, d, hx, hz, hw, hd, ch, lot);
        if (lot.water()) jetty(lf, w);
        return new VillaDistrict.Lot(lot.id(), lot.street(), lot.number(), lf, w, d, lot.tier(), lot.score(), lot.price(), fam, info,
                lot.water(), lot.view(), lot.corner(), lot.park());
    }

    static int g(B.Frame f, int x, int z) {
        return Terrain.h(f.wx(x, z), f.wz(x, z));
    }

    static void setG(B.Frame f, int x, int z, int y) {
        int u = f.wx(x, z), v = f.wz(x, z);
        if (!Canvas.inXZ(u, v)) return;
        Terrain.H[u - Canvas.MINX][v - Canvas.MINZ] = y;
        Canvas.setGround(u, v, y);
    }

    static int pavement(B.Frame f, int w, int d) {
        for (int x = 0; x < w; x++) {
            int y = VillaDistrict.roadY(f.wx(x, d), f.wz(x, d));
            if (y != Integer.MIN_VALUE) return Math.floorDiv(y + 1, 2);
        }
        return g(f, w / 2, d - 1);
    }

    /**
     * Garden ground: flat around the house, easing to the pavement at the front and to the natural slope at the back.
     * Where neighbouring cells differ by two or more the higher one gets a retaining wall.
     */
    static void grade(B.Frame f, int w, int d, int hx, int hz, int hw, int hd, int floor, int front) {
        int[][] target = new int[w][d];
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                int nat = g(f, x, z);
                double dh = Math.max(0, Math.max(Math.max(hx - x, x - (hx + hw - 1)), Math.max(hz - z, z - (hz + hd - 1))));
                double k = 1 - Terrain.smooth((dh - 2) / 6.0);
                double y = nat + (floor - 1 - nat) * k;
                if (z >= hz + hd) {
                    double t = (z - (hz + hd - 1)) / (double) Math.max(1, d - (hz + hd));
                    y = (floor - 1) + (front - (floor - 1)) * Terrain.smooth(t);
                }
                target[x][z] = (int) Math.round(y);
            }
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                if (VillaDistrict.road(u, v)) continue;
                int old = g(f, x, z), y = target[x][z];
                Canvas.fill(u, Math.min(old, y) - 3, v, u, y - 1, v, "dirt");
                Canvas.fill(u, y + 1, v, u, Math.max(old, y) + 2, v, "air");
                Canvas.set(u, y, v, "grass_block");
                setG(f, x, z, y);
            }
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                int y = target[x][z];
                for (int[] n : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + n[0], nz = z + n[1];
                    if (nx < 0 || nz < 0 || nx >= w || nz >= d) continue;
                    if (y - target[nx][nz] >= 2) {
                        int u = f.wx(x, z), v = f.wz(x, z);
                        for (int yy = target[nx][nz]; yy < y; yy++) Canvas.set(u, yy + 1 - 1, v, R.nextDouble() < 0.3 ? "mossy_stone_bricks" : "stone_bricks");
                        Canvas.set(u, y, v, "stone_bricks");
                    }
                }
            }
    }

    static void boundary(B.Frame f, int w, int d, Character ch, int gateX, int driveX) {
        String front = switch (ch) {
            case FORMAL -> R.nextBoolean() ? "pale_oak_fence" : "hedge";
            case NATURAL -> R.nextBoolean() ? "stone_brick_wall" : "spruce_fence";
            case URBAN -> "pale_oak_fence";
            case PRIVATE -> "hedge";
        };
        String side = ch == Character.PRIVATE ? "hedge" : R.nextBoolean() ? "hedge" : front.equals("hedge") ? "spruce_fence" : front;
        for (int x = 0; x < w; x++) {
            if (Math.abs(x - gateX) <= 0 || (x >= driveX && x <= driveX + 2)) {
                if (x == gateX && !front.equals("hedge")) put(f, x, d - 1, front.contains("fence") ? front + "_gate[facing=south,open=false]" : "air", false);
                continue;
            }
            put(f, x, d - 1, front, true);
        }
        for (int z = 0; z < d - 1; z++) {
            put(f, 0, z, side, true);
            put(f, w - 1, z, side, true);
        }
        for (int x = 1; x < w - 1; x++) put(f, x, 0, side, true);
    }

    /** One boundary element; hedges are two blocks of clipped leaves. */
    static void put(B.Frame f, int x, int z, String kind, boolean solid) {
        int u = f.wx(x, z), v = f.wz(x, z);
        if (!Canvas.inXZ(u, v)) return;
        if (VillaDistrict.road(u, v)) return;
        int y = Terrain.h(u, v);
        if (VillaDistrict.lake(u, v)) return;
        if (kind.equals("hedge")) {
            Canvas.set(u, y + 1, v, "oak_leaves[persistent=true]");
            if (Math.floorMod(x * 7 + z * 3, 11) != 0) Canvas.set(u, y + 2, v, "oak_leaves[persistent=true]");
        } else if (kind.equals("air")) {
            Canvas.set(u, y + 1, v, "air");
        } else Canvas.set(u, y + 1, v, kind.contains("[") ? B.rotate(kind, f.turns()) : kind);
    }

    static void path(B.Frame f, int doorX, int doorZ, int d, Character ch) {
        String mat = switch (ch) {
            case FORMAL -> "gravel";
            case NATURAL -> "stone_path";
            case URBAN -> "smooth_stone";
            case PRIVATE -> "stone_bricks";
        };
        for (int z = doorZ; z < d; z++) {
            int u = f.wx(doorX, z), v = f.wz(doorX, z);
            if (VillaDistrict.road(u, v)) break;
            int y = Terrain.h(u, v);
            String s = mat.equals("stone_path") ? ((z & 1) == 0 ? "cobblestone" : "grass_block") : mat;
            Canvas.set(u, y, v, s);
            if (z == doorZ) Canvas.set(u, y + 1, v, "air");
            if (ch == Character.FORMAL && (z - doorZ) % 4 == 2) {
                int lu = f.wx(doorX - 1, z), lv = f.wz(doorX - 1, z);
                Canvas.set(lu, Terrain.h(lu, lv) + 1, lv, "lantern");
            }
        }
    }

    static void driveway(B.Frame f, int x0, int d, int zEnd, Character ch) {
        String mat = ch == Character.NATURAL ? "gravel" : R.nextBoolean() ? "light_gray_concrete" : "stone_bricks";
        for (int x = x0; x <= x0 + 2; x++)
            for (int z = Math.max(1, zEnd); z < d; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                if (VillaDistrict.road(u, v)) break;
                Canvas.set(u, Terrain.h(u, v), v, mat);
                Canvas.set(u, Terrain.h(u, v) + 1, v, "air");
            }
    }

    static void mailbox(B.Frame f, int doorX, int d, VillaDistrict.Lot lot) {
        int x = doorX + 2, z = d - 1;
        int u = f.wx(x, z), v = f.wz(x, z);
        int y = Terrain.h(u, v);
        Canvas.set(u, y + 1, v, "spruce_fence");
        Canvas.set(u, y + 2, v, B.rotate("barrel[facing=south]", f.turns()));
        Town.label(u + 0.5, y + 3.4, v + 0.5, lot.street() + " " + lot.number(), "", "#FFFFFF", 0.55f);
    }

    static void beds(B.Frame f, int x0, int x1, int z, Character ch, int doorX) {
        for (int x = x0; x <= x1; x++) {
            if (Math.abs(x - doorX) <= 1) continue;
            int u = f.wx(x, z), v = f.wz(x, z);
            int y = Terrain.h(u, v);
            Canvas.set(u, y, v, ch == Character.FORMAL ? "coarse_dirt" : "podzol");
            String plant = switch (ch) {
                case FORMAL -> (x & 1) == 0 ? "flowering_azalea_leaves[persistent=true]" : "red_tulip";
                case NATURAL -> B.FLOWERS.get(R.nextInt(B.FLOWERS.size()));
                case URBAN -> R.nextBoolean() ? "potted_fern" : "fern";
                case PRIVATE -> R.nextBoolean() ? "azalea_leaves[persistent=true]" : "lilac[half=lower]";
            };
            if (Canvas.isAir(u, y + 1, v)) {
                Canvas.set(u, y + 1, v, plant.equals("potted_fern") ? "potted_fern" : plant);
                if (plant.startsWith("lilac")) Canvas.set(u, y + 2, v, "lilac[half=upper]");
                if (plant.equals("potted_fern")) Canvas.set(u, y, v, "smooth_stone");
            }
        }
    }

    /** Patio behind the house with furniture, and extras by lot size: shed, greenhouse, beds, swing, pool deck. */
    static void backyard(B.Frame f, int w, int hx, int hz, int hw, int floor, Character ch, VillaDistrict.Lot lot) {
        int pz0 = Math.max(2, hz - 4), pz1 = hz - 1;
        String patio = switch (ch) {
            case FORMAL -> "polished_andesite";
            case NATURAL -> "spruce_planks";
            case URBAN -> "smooth_stone";
            case PRIVATE -> "bricks";
        };
        for (int x = hx; x < hx + hw; x++)
            for (int z = pz0; z <= pz1; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                Canvas.set(u, Terrain.h(u, v), v, patio);
            }
        int tx = hx + hw / 2, tz = (pz0 + pz1) / 2;
        int u = f.wx(tx, tz), v = f.wz(tx, tz), y = Terrain.h(u, v);
        Canvas.set(u, y + 1, v, "spruce_fence");
        Canvas.set(u, y + 2, v, "white_carpet");
        for (int[] c : new int[][] {{-1, 0, 3}, {1, 0, 1}}) {
            int cu = f.wx(tx + c[0], tz), cv = f.wz(tx + c[0], tz);
            Canvas.setIfAir(cu, Terrain.h(cu, cv) + 1, cv, B.rotate(B.stairs("spruce", B.Dir.values()[c[2]]), f.turns()));
        }
        if (lot.tier().equals("PREMIUM") || lot.tier().equals("LARGE")) {
            Canvas.set(u, y + 3, v, "spruce_fence");
            for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) Canvas.setIfAir(u + du, y + 4, v + dv, du == 0 && dv == 0 ? "white_wool" : "white_carpet");
        }
        int lampU = f.wx(hx - 1, pz0), lampV = f.wz(hx - 1, pz0);
        Build.lamp(lampU, lampV);
        // extras in the back corners
        int free = hz - 2 - 2;
        if (free >= 5) {
            if (ch != Character.URBAN) greenhouseOrShed(f, 2, 2, ch);
            if (w > 20 && ch != Character.PRIVATE) swing(f, w - 7, 2);
            else if (w > 18) beds(f, w - 7, 3);
        }
    }

    static void greenhouseOrShed(B.Frame f, int x0, int z0, Character ch) {
        boolean greenhouse = ch == Character.NATURAL || ch == Character.FORMAL && R.nextBoolean();
        int y = g(f, x0 + 1, z0 + 1);
        for (int x = x0; x < x0 + 4; x++)
            for (int z = z0; z < z0 + 3; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                Canvas.set(u, y, v, greenhouse ? "gravel" : "spruce_planks");
                boolean edge = x == x0 || x == x0 + 3 || z == z0 || z == z0 + 2;
                for (int yy = y + 1; yy <= y + 2; yy++) Canvas.set(u, yy, v, edge ? (greenhouse ? "glass" : "red_terracotta") : "air");
                Canvas.set(u, y + 3, v, greenhouse ? "glass" : "dark_oak_slab[type=bottom]");
            }
        int du = f.wx(x0 + 1, z0 + 2), dv = f.wz(x0 + 1, z0 + 2);
        Canvas.set(du, y + 1, dv, "air");
        Canvas.set(du, y + 2, dv, "air");
        if (greenhouse) {
            int iu = f.wx(x0 + 1, z0 + 1), iv = f.wz(x0 + 1, z0 + 1);
            Canvas.set(iu, y, iv, "farmland[moisture=7]");
            Canvas.set(iu, y + 1, iv, "carrots[age=7]");
        } else {
            int iu = f.wx(x0 + 2, z0 + 1), iv = f.wz(x0 + 2, z0 + 1);
            Canvas.set(iu, y + 1, iv, "barrel[facing=up]");
            // bicycle stand next to the shed
            int bu = f.wx(x0 + 4, z0 + 1), bv = f.wz(x0 + 4, z0 + 1);
            Canvas.setIfAir(bu, Terrain.h(bu, bv) + 1, bv, "iron_bars");
        }
    }

    static void swing(B.Frame f, int x0, int z0) {
        int y = g(f, x0, z0);
        B.Frame s = new B.Frame(f.wx(x0, z0), y, f.wz(x0, z0), f.turns());
        s.fill(0, 1, 0, 0, 3, 0, "spruce_fence");
        s.fill(4, 1, 0, 4, 3, 0, "spruce_fence");
        s.fill(0, 4, 0, 4, 4, 0, "stripped_spruce_log[axis=x]");
        for (int x : new int[] {1, 3}) {
            s.set(x, 3, 0, "iron_chain[axis=y]");
            s.set(x, 2, 0, "spruce_slab[type=bottom]");
        }
    }

    static void beds(B.Frame f, int x0, int z0) {
        for (int x = x0; x < x0 + 5; x++)
            for (int z = z0; z < z0 + 3; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                int y = Terrain.h(u, v);
                boolean edge = x == x0 || x == x0 + 4 || z == z0 || z == z0 + 2;
                Canvas.set(u, y, v, edge ? "spruce_planks" : "farmland[moisture=7]");
                if (!edge) Canvas.set(u, y + 1, v, R.nextBoolean() ? "carrots[age=7]" : "beetroots[age=3]");
            }
    }

    static void trees(B.Frame f, int w, int d, int hx, int hz, int hw, int hd, Character ch, VillaDistrict.Lot lot) {
        int n = switch (ch) {
            case PRIVATE -> 5;
            case NATURAL -> 4;
            case FORMAL -> 2;
            case URBAN -> 1;
        } + (w * d > 700 ? 2 : 0);
        for (int k = 0, tries = 0; k < n && tries < 60; tries++) {
            int x = 2 + R.nextInt(w - 4), z = 2 + R.nextInt(d - 4);
            if (x > hx - 3 && x < hx + hw + 2 && z > hz - 5 && z < hz + hd + 2) continue;
            if (z > d - 4) continue;
            int u = f.wx(x, z), v = f.wz(x, z);
            int y = Terrain.h(u, v);
            if (!Canvas.get(u, y, v).equals("grass_block") || !Canvas.isAir(u, y + 1, v)) continue;
            boolean ok = switch (ch) {
                case FORMAL -> fruitTree(u, v, y);
                case NATURAL -> R.nextBoolean() ? Nature.birch(u, v, y, 7 + R.nextInt(3)) : fruitTree(u, v, y);
                default -> R.nextInt(3) == 0 ? Nature.spruce(u, v, y, 8 + R.nextInt(4)) : Nature.oak(u, v, y, 5 + R.nextInt(2));
            };
            if (ok) k++;
        }
        // a lawn with a few tufts and flowers
        for (int x = 1; x < w - 1; x++)
            for (int z = 1; z < d - 1; z++) {
                int u = f.wx(x, z), v = f.wz(x, z);
                int y = Terrain.h(u, v);
                if (!Canvas.get(u, y, v).equals("grass_block") || !Canvas.isAir(u, y + 1, v)) continue;
                double r = R.nextDouble();
                if (ch == Character.NATURAL && r < 0.25) Canvas.set(u, y + 1, v, r < 0.12 ? "short_grass" : B.FLOWERS.get(R.nextInt(B.FLOWERS.size())));
                else if (r < 0.04) Canvas.set(u, y + 1, v, "short_grass");
            }
    }

    /** A small apple or cherry tree. */
    static boolean fruitTree(int u, int v, int y) {
        Canvas.fill(u, y + 1, v, u, y + 3, v, "oak_log[axis=y]");
        String leaves = R.nextBoolean() ? "cherry_leaves[persistent=true]" : "oak_leaves[persistent=true]";
        for (int du = -2; du <= 2; du++)
            for (int dy = 0; dy <= 2; dy++)
                for (int dv = -2; dv <= 2; dv++) {
                    if (Math.sqrt(du * du + dv * dv + (dy - 1) * (dy - 1) * 1.5) > 2.3) continue;
                    Canvas.setIfAir(u + du, y + 3 + dy, v + dv, leaves);
                }
        return true;
    }

    /** A jetty from the back of a waterfront lot out over the lake, with a rowing boat. */
    static void jetty(B.Frame f, int w) {
        int x = w / 2;
        for (int k = 1; k <= 22; k++) {
            int u = f.wx(x, -k), v = f.wz(x, -k);
            if (!Canvas.inXZ(u, v)) return;
            if (!VillaDistrict.lake(u, v)) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int uu = f.wx(x + dx, -k), vv = f.wz(x + dx, -k);
                Canvas.set(uu, Canvas.SEA + 1, vv, "spruce_planks");
                if (k % 4 == 0 && dx != 0) Canvas.fill(uu, Terrain.h(uu, vv) + 1, vv, uu, Canvas.SEA, vv, "spruce_log[axis=y]");
            }
            int end = k;
            int nu = f.wx(x, -k - 1), nv = f.wz(x, -k - 1);
            if (k >= 8 || !VillaDistrict.lake(nu, nv)) {
                int lu = f.wx(x - 1, -end), lv = f.wz(x - 1, -end);
                Canvas.set(lu, Canvas.SEA + 2, lv, "spruce_fence");
                Canvas.set(lu, Canvas.SEA + 3, lv, "lantern");
                f.summon(x + 2.5, -1.4, -end + 0.5, "oak_boat", "{Tags:[\"" + Canvas.TAG + "\"]}");
                return;
            }
        }
    }
}
