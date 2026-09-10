package com.course.dbms.engine.index;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 内存 B+ 树的白盒测试：分裂、树高、重复键、范围查找、有序性、清空。
 *
 * 约定：插入时把 RID 的【页号】当成键，这样 range/search 只回 RID 也能反推出键序列来断言。
 */
public class BPlusTreeTest {

    private BPlusTree tree;

    @Before public void setup() { tree = new BPlusTree(); }

    /** 键 key 落在页号 = key、槽位 = slot 的槽位上。 */
    private void put(int key, int slot) {
        tree.insert(Integer.valueOf(key), key, slot);
    }

    /** 从 RID 反推键序列（见类注释的约定）。 */
    private List<Integer> keysOf(List<int[]> rids) {
        List<Integer> ks = new ArrayList<>();
        for (int[] rid : rids) ks.add(Integer.valueOf(rid[0]));
        return ks;
    }

    private List<Integer> ints(int... vs) {
        List<Integer> l = new ArrayList<>();
        for (int v : vs) l.add(Integer.valueOf(v));
        return l;
    }

    @Test public void insertManySplitsAndGrowsTree() {
        for (int k = 0; k < 100; k++) put(k, 0);
        assertEquals("100 条索引项一条不丢", 100, tree.size());
        // 阶很小（MAX_KEYS=4），100 条必然经历多轮分裂，树高应明显长起来
        assertTrue("应经历多次分裂使树高 >= 3，实际 " + tree.height(), tree.height() >= 3);
        // 结构完整性：每个键都还能查到，且查到的正是自己
        for (int k = 0; k < 100; k++) {
            List<int[]> hits = tree.search(Integer.valueOf(k));
            assertEquals("键 " + k + " 应有且只有一条", 1, hits.size());
            assertEquals(k, hits.get(0)[0]);
        }
    }

    @Test public void searchReturnsAllRidsForDuplicateKeys() {
        put(30, 0);
        put(30, 1);
        put(30, 2);
        put(10, 0);
        List<int[]> hits = tree.search(Integer.valueOf(30));
        assertEquals("重复键应返回全部 RID", 3, hits.size());
        // 相同键按插入先后排列（插入点取"第一个大于 key 的位置"）
        assertEquals(0, hits.get(0)[1]);
        assertEquals(1, hits.get(1)[1]);
        assertEquals(2, hits.get(2)[1]);
        assertTrue(tree.search(Integer.valueOf(99)).isEmpty());
    }

    @Test public void rangeRespectsInclusiveFlags() {
        for (int k = 0; k < 10; k++) put(k, 0);
        assertEquals(ints(3, 4, 5, 6), keysOf(tree.range(Integer.valueOf(3), true, Integer.valueOf(7), false)));
        assertEquals(ints(4, 5, 6, 7), keysOf(tree.range(Integer.valueOf(3), false, Integer.valueOf(7), true)));
        assertEquals(ints(3, 4, 5, 6, 7), keysOf(tree.range(Integer.valueOf(3), true, Integer.valueOf(7), true)));
    }

    @Test public void rangeSupportsOpenEnds() {
        for (int k = 0; k < 10; k++) put(k, 0);
        assertEquals("左开：>= -∞ 到 <= 2", ints(0, 1, 2),
                keysOf(tree.range(null, false, Integer.valueOf(2), true)));
        assertEquals("右开：>= 7 到 +∞", ints(7, 8, 9),
                keysOf(tree.range(Integer.valueOf(7), true, null, false)));
        assertEquals("两侧都开 = 全表", 10,
                keysOf(tree.range(null, false, null, false)).size());
        assertTrue("下界超出所有键", tree.search(Integer.valueOf(100)).isEmpty());
        assertTrue("范围完全落在键之外", tree.range(Integer.valueOf(50), true, Integer.valueOf(60), true).isEmpty());
    }

    @Test public void rangeSpansLeafChainInKeyOrder() {
        // 乱序插入，验证叶子链把所有叶子按序串起来（跨多个叶子仍有序）
        List<Integer> shuffled = new ArrayList<>();
        for (int k = 0; k < 40; k++) shuffled.add(Integer.valueOf(k));
        Collections.shuffle(shuffled, new java.util.Random(2024));   // 固定种子，可复现
        for (Integer k : shuffled) put(k.intValue(), 0);

        List<Integer> all = keysOf(tree.range(null, false, null, false));
        assertEquals(40, all.size());
        List<Integer> sorted = new ArrayList<>(all);
        Collections.sort(sorted);
        assertEquals("范围查结果应按键有序", sorted, all);
    }

    @Test public void clearEmptiesTree() {
        for (int k = 0; k < 20; k++) put(k, 0);
        tree.clear();
        assertEquals(0, tree.size());
        assertEquals(0, tree.height());
        assertTrue(tree.range(null, false, null, false).isEmpty());
        assertTrue(tree.search(Integer.valueOf(5)).isEmpty());
        // 清空后还能继续用
        put(5, 9);
        assertEquals(1, tree.size());
        assertEquals(1, tree.search(Integer.valueOf(5)).size());
    }

    @Test public void emptyTreeQueriesAreSafe() {
        assertEquals(0, tree.size());
        assertTrue(tree.search(Integer.valueOf(1)).isEmpty());
        assertTrue(tree.range(Integer.valueOf(1), true, Integer.valueOf(9), true).isEmpty());
    }
}
