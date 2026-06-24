/*
 * Copyright (c) 2026, Amazon.com, Inc. or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

/*
 * @test id=shenandoah
 * @summary Identity hash codes must stay stable when Shenandoah Full GC
 *          relocates humongous objects (compact object headers).
 * @bug 8388XYZ
 * @requires vm.gc.Shenandoah
 * @requires vm.opt.UseCompactObjectHeaders == null | vm.opt.UseCompactObjectHeaders == true
 * @run main/othervm -XX:+UseCompactObjectHeaders -XX:+UseShenandoahGC -Xms256m -Xmx256m
 *      -Xint TestHashCodeHumongousFullGC
 */

/*
 * @test id=shenandoah-generational
 * @summary Identity hash codes must stay stable when generational Shenandoah
 *          Full GC relocates humongous objects (compact object headers).
 * @bug 8388XYZ
 * @requires vm.gc.Shenandoah
 * @requires vm.opt.UseCompactObjectHeaders == null | vm.opt.UseCompactObjectHeaders == true
 * @run main/othervm -XX:+UseCompactObjectHeaders -XX:+UseShenandoahGC
 *      -XX:ShenandoahGCMode=generational -Xms256m -Xmx256m -Xint
 *      TestHashCodeHumongousFullGC
 */

/**
 * Regression test for a missing identity-hash freeze in
 * ShenandoahFullGC::compact_humongous_objects().
 *
 * With -XX:+UseCompactObjectHeaders an object that has been identity-hashed but
 * not yet GC-expanded is in mark-word hashctrl state 0b01, and its identity hash
 * is recomputed from the object's current heap address on every read. When the
 * GC relocates the object it must "expand" it (state 0b11) and freeze the hash
 * into a hidden word -- the regular-object compaction path does this with
 * initialize_hash_if_necessary(), but the humongous path called only
 * reinit_mark() (which preserves the 0b01 state). A relocated humongous object
 * therefore recomputed its hash from the new address, returning a different
 * value for the same live object and violating the Object.hashCode() contract.
 *
 * The test keeps several hashed humongous arrays live and drives Shenandoah into
 * Full GC under allocation pressure (small heap), which slides the humongous
 * objects to new addresses, then checks the identity hash and contents are
 * unchanged. On the buggy VM the hashes change after a relocating Full GC.
 */
public class TestHashCodeHumongousFullGC {

    // Humongous = larger than a Shenandoah region. ~9.6 MB clears any default
    // region size, and keeping many of them in a small heap forces the Full GC
    // to slide (relocate) the survivors rather than just reclaim garbage.
    static final int ARRAY_WORDS = 1200 * 1024;  // ~9.6 MB long[]
    static final int KEEP = 20;
    static final int ROUNDS = 200;

    static volatile Object sink;

    public static void main(String[] args) {
        long[][] live = new long[KEEP][];
        int[] hash = new int[KEEP];
        for (int i = 0; i < KEEP; i++) {
            live[i] = new long[ARRAY_WORDS];
            live[i][0] = 0xC0DE0000L + i;
            live[i][ARRAY_WORDS - 1] = ~(long) i;
            hash[i] = System.identityHashCode(live[i]);   // -> hashed, not expanded
        }

        // Drive Full GC via allocation pressure so the live humongous objects are
        // relocated (compacted) rather than simply reclaimed.
        for (int round = 0; round < ROUNDS; round++) {
            java.util.ArrayList<Object> garbage = new java.util.ArrayList<>();
            try {
                for (int k = 0; k < 8; k++) garbage.add(new long[1000 * 1024]); // transient humongous
                for (int k = 0; k < 2000; k++) garbage.add(new byte[16 * 1024]);
            } catch (OutOfMemoryError e) {
                // expected under the tight heap; drop the garbage and continue
            }
            garbage = null;
            sink = new byte[1024];

            for (int i = 0; i < KEEP; i++) {
                int h = System.identityHashCode(live[i]);
                if (h != hash[i]) {
                    throw new RuntimeException("identity hash changed for humongous object " + i
                            + " after GC round " + round + ": expected " + hash[i] + " got " + h);
                }
                if (live[i][0] != (0xC0DE0000L + i) || live[i][ARRAY_WORDS - 1] != ~(long) i) {
                    throw new RuntimeException("humongous object " + i + " content corrupted after round " + round);
                }
            }
        }

        System.out.println("PASSED: humongous identity hashes stable across Shenandoah Full GC");
    }
}
