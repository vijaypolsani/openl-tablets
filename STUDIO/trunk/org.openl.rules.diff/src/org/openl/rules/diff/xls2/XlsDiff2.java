package org.openl.rules.diff.xls2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.openl.rules.diff.print.SimpleDiffTreePrinter;
import org.openl.rules.diff.tree.DiffTreeNode;
import org.openl.rules.diff.xls.XlsProjectionDiffer;
import org.openl.rules.lang.xls.XlsHelper;
import org.openl.rules.lang.xls.binding.XlsMetaInfo;
import org.openl.rules.lang.xls.syntax.TableSyntaxNode;
import org.openl.rules.lang.xls.syntax.XlsModuleSyntaxNode;
import org.openl.rules.table.ICell;
import org.openl.rules.table.IGridTable;

/**
 * Find difference between two XLS files.
 * It compares per Table.
 * <p>
 * Incomplete.
 * Need AxB vs CxD implementation.
 * Need to be optimal.
 * 
 * @author Aleh Bykhavets
 * 
 */
public class XlsDiff2 {
    private List<XlsTable> tables1;
    private List<XlsTable> tables2;

    // Same Sheet, Location (Start, End), Header
    private static final String GUESS_SAME = "1-same";
    // Same Sheet, Location (Start, End)
    private static final String GUESS_SAME_PLACE = "2-samePlace";
    // Same Sheet, Start, Header
    private static final String GUESS_CAN_BE_SAME = "3-canBeSame";
    // Same Sheet, Header
    private static final String GUESS_MAY_BE_SAME = "4-mayBeSame";

    private Map<String, List<DiffPair>> diffGuess;

    public XlsDiff2() {
        // TreeMap -- Key as a weight
        diffGuess = new TreeMap<String, List<DiffPair>>();
    }

    public static void main(String[] args) {
        XlsDiff2 diff = new XlsDiff2();

        diff.load("test/diff-1.xls", "test/diff-2.xls");
        diff.diff();
        DiffTreeNode root = diff.buildTree();
        SimpleDiffTreePrinter p = new SimpleDiffTreePrinter(root, System.out);
        p.print();
    }

    protected List<XlsTable> load(String fileName) {
        XlsMetaInfo xmi = XlsHelper.getXlsMetaInfo(fileName);
        XlsModuleSyntaxNode xsn = xmi.getXlsModuleNode();

        TableSyntaxNode[] nodes = xsn.getXlsTableSyntaxNodes();
        List<XlsTable> tables = new ArrayList<XlsTable>(nodes.length);
        for (TableSyntaxNode node : nodes) {
            tables.add(new XlsTable(node));
        }

        return tables;
    }

    public DiffTreeNode diffFiles(String xlsFile1, String xlsFile2) {
        load(xlsFile1, xlsFile2);
        diff();
        return buildTree();
    }

    public void load(String xlsFile1, String xlsFile2) {
        tables1 = load(xlsFile1);
        tables2 = load(xlsFile2);
    }

    protected void add(String guess, DiffPair r) {
        List<DiffPair> list = diffGuess.get(guess);
        if (list == null) {
            list = new LinkedList<DiffPair>();
            diffGuess.put(guess, list);
        }
        list.add(r);
    }

    protected void diff() {
        // 1. Simple cases
        iterate(new IterClosure() {
            // @Override
            public boolean remove(XlsTable t1, XlsTable t2) {
                if (t1.getSheetName().equals(t2.getSheetName())) {
                    String s1 = t1.getLocation().getStart().toString();
                    String s2 = t2.getLocation().getStart().toString();
                    if (s1.equals(s2)) {
                        boolean sameName = t1.getTableName().equals(t2.getTableName());

                        String e1 = t1.getLocation().getEnd().toString();
                        String e2 = t2.getLocation().getEnd().toString();
                        if (e1.equals(e2)) {
                            if (sameName) {
                                add(GUESS_SAME, new DiffPair(t1, t2));
                            } else {
                                add(GUESS_SAME_PLACE, new DiffPair(t1, t2));
                            }
                            return true;
                        } else if (sameName) {
                            add(GUESS_CAN_BE_SAME, new DiffPair(t1, t2));
                            return true;
                        }
                    }
                }
                return false;
            }
        });

        // 2. Sheet and name seems the same
        iterate(new IterClosure() {
            // @Override
            public boolean remove(XlsTable t1, XlsTable t2) {
                if (t1.getSheetName().equals(t2.getSheetName())) {
                    boolean sameName = t1.getTableName().equals(t2.getTableName());
                    if (sameName) {
                        add(GUESS_MAY_BE_SAME, new DiffPair(t1, t2));
                        return true;
                    }
                }
                return false;
            }
        });
    }

    protected void iterate(IterClosure closure) {
        Iterator<XlsTable> i1 = tables1.iterator();
        while (i1.hasNext()) {
            XlsTable t1 = i1.next();

            Iterator<XlsTable> i2 = tables2.iterator();
            while (i2.hasNext()) {
                XlsTable t2 = i2.next();

                if (closure.remove(t1, t2)) {
                    i1.remove();
                    i2.remove();
                    break;
                }
            }
        }
    }

    protected DiffTreeNode buildTree() {
        DiffTreeBuilder2 builder = new DiffTreeBuilder2();
        builder.setProjectionDiffer(new XlsProjectionDiffer());

        // 1. Pairs v1:v2
        for (String guess : diffGuess.keySet()) {
            for (DiffPair pair : diffGuess.get(guess)) {
                checkGrid(pair);
                builder.add(pair);
            }
        }

        // 2. Lonely tables
        // 2.1 v1 only
        for (XlsTable t : tables1) {
            builder.add(new DiffPair(t, null));
        }
        // 2.2 v2 only
        for (XlsTable t : tables2) {
            builder.add(new DiffPair(null, t));
        }

        return builder.compare();
    }

    protected void checkGrid(DiffPair pair) {
        IGridTable grid1 = pair.getTable1().getTable().getGridTable();
        IGridTable grid2 = pair.getTable2().getTable().getGridTable();

        List<ICell> diff1 = new ArrayList<ICell>();
        List<ICell> diff2 = new ArrayList<ICell>();

        if (grid1.getWidth() == grid2.getWidth() && grid1.getHeight() == grid2.getHeight()) {
            //Same Size
            compareRows(grid1, grid2, diff1);
            compareRows(grid2, grid1, diff2);
        } else if (grid1.getWidth() == grid2.getWidth()) {
            //Same Width
            //May be ROWs were changed
            compareRows(grid1, grid2, diff1);
            compareRows(grid2, grid1, diff2);
        } else if (grid1.getHeight() == grid2.getHeight()) {
            //Same Height
            compareCols(grid1, grid2, diff1);
            compareCols(grid2, grid1, diff2);
        } else {
            //Diff Size
            // TODO Implement AxB vs CxD algorithm
        }

        if (!diff1.isEmpty()) {
            pair.setDiffCells1(diff1);
        }
        if (!diff2.isEmpty()) {
            pair.setDiffCells2(diff2);
        }
    }

    protected void compareRows(IGridTable grid1, IGridTable grid2, List<ICell> diff) {
        //TODO Review the algorithm. Seems like it is not optimal.
        boolean matched[] = new boolean[grid1.getHeight()];
        int[] match2 = new int[grid1.getHeight()];
        // The getDiffsCount() is used instead of map to prevent OutOfMemoryError. Not so fast but less memory-consumptive.
//        int[][] map = new int[grid1.getHeight()][grid2.getHeight()];

        int y2s = 0;
        for (int y1 = 0; y1 < grid1.getHeight(); y1++) {
            for (int y2 = y2s; y2 < grid2.getHeight(); y2++) {
                int nDiff = getDiffsCount(grid1, grid2, y1, y2);
                // faster but too greed, i.e. non-optimal
                if (nDiff == 0) {
                    matched[y1] = true;
                    match2[y1] = y2;
                    y2s = y2+1;
                    break;
                }
//                map[y1][y2] = nDiff;
            }
        }

        // list unmatched rows
        int nPart;
        do {
            nPart = 0;

            y2s = 0;
            for (int y1 = 0; y1 < grid1.getHeight(); y1++) {
                if (matched[y1]) {
                    y2s = match2[y1] + 1;
                    continue;
                }

                int y2e = grid2.getHeight();
                int y1e = y1;
                for (; y1e < grid1.getHeight(); y1e++) {
                    if (matched[y1e]) {
                        y2e = match2[y1e];
                        break;
                    }
                }

                if (y2s < grid2.getHeight()) {
                    // find best match
                    int i1 = y1;
                    int i2 = y2s;
//                    int n = map[i1][i2];
                    int n = getDiffsCount(grid1, grid2, i1, i2);
                    for (int y = y1; y < y1e; y++) {
                        for (int y2 = y2s; y2 < y2e; y2++) {
//                            int m = map[y][y2];
                            int m = getDiffsCount(grid1, grid2, y, y2);
                            if (m < n) {
                                n = m;
                                i1 = y;
                                i2 = y2;
                            }
                        }
                    }

                    // partial match
                    matched[i1] = true;
                    match2[i1] = i2;
                    for (int x = 0; x < grid1.getWidth(); x++) {
                        ICell c1 = grid1.getCell(x, i1);
                        ICell c2 = grid2.getCell(x, i2);
                        if (!compareCells(c1, c2)) {
                            diff.add(c1);
                        }
                    }

                    nPart++;
                    // trick, 1 step back
                    y1 = i1 - 1;
                }
            }
        } while (nPart > 0);

        // list unmatched rows
        // just in case
        for (int y1 = 0; y1 < grid1.getHeight(); y1++) {
            if (matched[y1]) continue;

            for (int x = 0; x < grid1.getWidth(); x++) {
                ICell c1 = grid1.getCell(x, y1);
                diff.add(c1);
            }
        }
    }

    private int getDiffsCount(IGridTable grid1, IGridTable grid2, int y1, int y2) {
        int nDiff = 0;
        for (int x = 0; x < grid1.getWidth(); x++) {
            ICell c1 = grid1.getCell(x, y1);
            ICell c2 = grid2.getCell(x, y2);
            if (!compareCells(c1, c2)) {
                nDiff++;
            }
        }
        return nDiff;
    }

    protected void compareCols(IGridTable grid1, IGridTable grid2, List<ICell> diff) {
        // compareRows is hard enough :)
        // let reuse it
        List<ICell> iDiff = new ArrayList<ICell>();
        compareRows(grid1.transpose(), grid2.transpose(), iDiff);

        // fix diff -- invert coordinates
        for (ICell c : iDiff) {
            diff.add(grid1.getCell(c.getRow(), c.getColumn()));
        }
    }

    protected boolean compareCells(ICell c1, ICell c2) {
        Object o1 = c1.getObjectValue();
        Object o2 = c2.getObjectValue();

        // TODO compare value, comment, value and so on...
        if (o1 == null) {
            return (o1 == o2);
        } else {
            return o1.equals(o2);
        }
    }
}
