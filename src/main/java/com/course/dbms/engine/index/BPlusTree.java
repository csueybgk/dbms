package com.course.dbms.engine.index;

import com.course.dbms.engine.exec.op.Compare;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存 B+ 树：索引的物理结构。
 *
 * 与课本一致的两条约束：
 *   1) 所有数据只存在【叶子】节点，内部节点只存分隔键（故任何查询都走到叶子，树高一致）；
 *   2) 叶子之间用 next 指针串成【有序链表】，范围查找沿链表右扫，不必反复回到根。
 *
 * 键的约定（决定了所有查找/插入的走向）：内部节点的 keys[i] 是其右子树的下界，
 * 于是 subtree(children[i]) ⊆ [keys[i-1], keys[i])。相等键一律归入右孩子，
 * 因而"第一个大于 key 的位置"这一个函数同时充当内部节点的选孩子规则与叶子节点的插入点规则。
 *
 * 分裂规则：节点键数超过 {@link #MAX_KEYS} 即分裂成左右两个节点，
 * 叶子的分隔键【复制上提】（键仍留在右叶子），内部节点的分隔键【移出上提】；
 * 根分裂时新建根，树高 +1。
 *
 * 条目存 (键, RID) 且【允许重复键】（索引列如 age 上重复值很常见），
 * 所以叶子用两个平行列表而不是 Map。
 */
public class BPlusTree {

    /** 单个节点最多容纳的键数，超过即分裂（阶 = MAX_KEYS + 1）。取 4 便于小数据触发分裂、方便演示与测试。 */
    public static final int MAX_KEYS = 4;

    /** 树节点：叶子存 (键,RID)，内部节点存 (键,孩子)。 */
    private static final class Node {
        final boolean leaf;
        final List<Object> keys = new ArrayList<>();          // 节点内升序
        final List<Node> children = new ArrayList<>();        // 内部节点：keys.size() + 1 个
        final List<int[]> rids = new ArrayList<>();           // 叶子节点：与 keys 一一对应
        Node next;                                           // 叶子链（仅叶子使用）

        Node(boolean leaf) { this.leaf = leaf; }
    }

    /** 一次分裂的结果：上提给父节点的分隔键 + 新分裂出的右兄弟。 */
    private static final class Split {
        final Object key;
        final Node right;
        Split(Object key, Node right) { this.key = key; this.right = right; }
    }

    private Node root;
    private int size;

    /** 插入一条索引项：key 为索引列的值，RID = (页号, 槽位)。允许重复键。 */
    public void insert(Object key, int pageNo, int slot) {
        if (root == null) {
            root = new Node(true);
            root.keys.add(key);
            root.rids.add(new int[] { pageNo, slot });
            size = 1;
            return;
        }
        Split s = insertInto(root, key, new int[] { pageNo, slot });
        if (s != null) {                       // 根分裂 → 树高 +1
            Node newRoot = new Node(false);
            newRoot.keys.add(s.key);
            newRoot.children.add(root);
            newRoot.children.add(s.right);
            root = newRoot;
        }
        size++;
    }

    /** 递归下沉插入；返回非 null 表示本节点分裂，需要父节点接纳 (分隔键, 右兄弟)。 */
    private Split insertInto(Node n, Object key, int[] rid) {
        int i = upperBound(n, key);
        if (n.leaf) {
            n.keys.add(i, key);
            n.rids.add(i, rid);
            if (n.keys.size() <= MAX_KEYS) return null;
            int mid = n.keys.size() / 2;                  // 叶子：复制上提，mid 处的键仍留在右叶子
            Node right = new Node(true);
            right.keys.addAll(new ArrayList<>(n.keys.subList(mid, n.keys.size())));
            right.rids.addAll(new ArrayList<>(n.rids.subList(mid, n.rids.size())));
            n.keys.subList(mid, n.keys.size()).clear();
            n.rids.subList(mid, n.rids.size()).clear();
            right.next = n.next;                          // 维护叶子链：n → right → 原 n.next
            n.next = right;
            return new Split(right.keys.get(0), right);
        }
        Split s = insertInto(n.children.get(i), key, rid);
        if (s == null) return null;
        n.keys.add(i, s.key);
        n.children.add(i + 1, s.right);
        if (n.keys.size() <= MAX_KEYS) return null;
        int mid = n.keys.size() / 2;                      // 内部节点：移出上提，mid 处的键不再留在本节点
        Object up = n.keys.get(mid);
        Node right = new Node(false);
        right.keys.addAll(new ArrayList<>(n.keys.subList(mid + 1, n.keys.size())));
        right.children.addAll(new ArrayList<>(n.children.subList(mid + 1, n.children.size())));
        n.keys.subList(mid, n.keys.size()).clear();
        n.children.subList(mid + 1, n.children.size()).clear();
        return new Split(up, right);
    }

    /**
     * 第一个键【大于】key 的位置。内部节点用它选孩子（相等键在右孩子的下界里），
     * 叶子节点用它选插入点（相同键插到已有等键之后，保持稳定顺序）。
     */
    private static int upperBound(Node n, Object key) {
        int i = 0;
        while (i < n.keys.size() && Compare.compare(n.keys.get(i), key) <= 0) i++;
        return i;
    }

    /** 点查：返回所有键等于 key 的 RID（按键序）。 */
    public List<int[]> search(Object key) {
        return range(key, true, key, true);
    }

    /**
     * 范围查：lo/hi 为 null 表示该侧无界，loInc/hiInc 为是否含端点。
     * 先下沉到可能含 lo 的叶子，再沿叶子链右扫，结果天然按键有序。
     */
    public List<int[]> range(Object lo, boolean loInc, Object hi, boolean hiInc) {
        List<int[]> out = new ArrayList<>();
        if (root == null) return out;
        Node n = root;
        while (!n.leaf) n = n.children.get(lo == null ? 0 : upperBound(n, lo));

        while (n != null) {
            for (int j = 0; j < n.keys.size(); j++) {
                Object k = n.keys.get(j);
                if (lo != null) {
                    int c = Compare.compare(k, lo);
                    if (c < 0 || (c == 0 && !loInc)) continue;      // 尚未进入范围，继续右扫
                }
                if (hi != null) {
                    int c = Compare.compare(k, hi);
                    if (c > 0 || (c == 0 && !hiInc)) return out;    // 叶内有序：越过上界即可收工
                }
                out.add(n.rids.get(j));
            }
            n = n.next;
        }
        return out;
    }

    /** 索引项总数。 */
    public int size() { return size; }

    /** 树高（只有叶子时为 1；空树为 0）。用于测试与讲解"分裂确实抬高了树"。 */
    public int height() {
        int h = 0;
        Node n = root;
        while (n != null) {
            h++;
            n = n.leaf ? null : n.children.get(0);
        }
        return h;
    }

    /** 清空整棵树（重建索引 / 回滚撤销时用）。 */
    public void clear() {
        root = null;
        size = 0;
    }
}
