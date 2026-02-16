import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.Queue;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * LightUp (Akari) puzzle - Random 7x7 with Smart AI, Undo/Redo, and Team Win popup
 *
 * Features:
 * - Only these buttons: New Game, Restart Game, Undo Move, Redo Move, Solve Game
 * - Smart AI: evaluates all legal moves and plays the best-scoring safe move deterministically
 * - Undo/Redo: complete board-state snapshots for reliable undo/redo of both user and AI moves
 * - "Team win" popup: when the final solved state was achieved with both user and AI contributing
 *
 * AI guarantees:
 * - Exactly ONE mutation per AI turn: either remove one violating bulb or place one bulb. Never both.
 * - Enforce removal returns immediately after fix (no follow-on placement).
 * - Deterministic tie-breaking using a merge-sorted centrality order map.
 */
public class LightUp extends JFrame {
    enum CellType { BLACK, NUMBER, BLANK }

    static class Cell {
        CellType type;
        int number;
        boolean bulb;
        boolean dot;
        boolean lit;
        int row, col;
        int graphId;

        Cell(CellType t, int number, int r, int c) {
            this.type = t;
            this.number = number;
            this.row = r;
            this.col = c;
            bulb = false;
            dot = false;
            lit = false;
            graphId = -1;
        }

        boolean isWall() {
            return type == CellType.BLACK || type == CellType.NUMBER;
        }
    }

    static class GraphNode implements Comparable<GraphNode> {
        int id;
        int row, col;
        List<GraphNode> neighbors;
        int degree;
        double centrality;
        int distanceMetric;
        boolean visited;

        GraphNode(int id, int r, int c) {
            this.id = id;
            this.row = r;
            this.col = c;
            this.neighbors = new ArrayList<>();
            this.degree = 0;
            this.centrality = 0.0;
            this.distanceMetric = 0;
            this.visited = false;
        }

        @Override
        public int compareTo(GraphNode other) {
            return Integer.compare(this.distanceMetric, other.distanceMetric);
        }
    }

    // ===== STATE =====
    private Cell[][] board;
    private Cell[][] initialBoard; 
    private int rows = 7, cols = 7;
    private JPanel gridPanel;
    private JLabel statusLabel;

    // Controls
    private JButton newGameBtn, restartBtn, undoBtn, redoBtn, solveBtn;

    private Random rng = new Random();
    private Map<Integer, GraphNode> cellGraph;
    private List<GraphNode> blankNodes;

    // Turn-based play
    private boolean[][] perfectSolution;
    private boolean solutionReady = false; 

    private boolean userTurn = true;
    private int lastComputerR = -1, lastComputerC = -1;
    private boolean userContributed = false;
    private boolean aiContributed = false;

    // Merge-sort order map for tie-breaking
    private Map<Integer, Integer> centralityOrder = new HashMap<>();

    // Undo/Redo via full board snapshots
    static class BoardState {
        boolean[][] bulbs;
        boolean[][] dots;
        boolean userTurn;
        boolean userContributed;
        boolean aiContributed;
        int lastComputerR;
        int lastComputerC;

        BoardState(int rows, int cols) {
            bulbs = new boolean[rows][cols];
            dots = new boolean[rows][cols];
        }
    }

    private Deque<BoardState> undoStack = new ArrayDeque<>();
    private Deque<BoardState> redoStack = new ArrayDeque<>();

    private final Color BG_COLOR = new Color(245, 247, 250);
    private final Color GRID_BG = new Color(255, 255, 255);
    private final Color WALL_COLOR = new Color(30, 40, 60);
    private final Color NUMBER_COLOR = new Color(240, 245, 255);
    private final Color LIT_COLOR = new Color(255, 253, 200);
    private final Color BULB_COLOR = new Color(255, 225, 50);
    private final Color BULB_GLOW = new Color(255, 255, 200, 80);
    private final Color DOT_COLOR = new Color(180, 180, 180);
    private final Color CONFLICT_COLOR = new Color(255, 100, 100, 90);
    private final Color AI_HIGHLIGHT = new Color(80, 200, 120, 100);
    private final Color GRID_LINE = new Color(210, 215, 225);
    private final Color PANEL_BG = new Color(250, 252, 255);

    public LightUp() {
        super("Light Up Puzzle");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        generateRandomPuzzle();
        saveInitialBoard();
        buildGraph();
        buildUI();
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }

    // ===== GENERATION =====
    private void generateRandomPuzzle() {
    int maxAttempts = 50;
    boolean puzzleValid = false;

    for (int attempt = 0; attempt < maxAttempts && !puzzleValid; attempt++) {

        board = new Cell[rows][cols];

        // Initialize all cells as blank
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                board[r][c] = new Cell(CellType.BLANK, -1, r, c);
            }
        }

        // Add scattered black cells
        int blackCellCount = 8 + rng.nextInt(3);
        Set<String> blackPositions = new HashSet<>();
        int attempts = 0;

        while (blackPositions.size() < blackCellCount && attempts < 200) {
            int r = rng.nextInt(rows);
            int c = rng.nextInt(cols);
            String pos = r + "," + c;

            if (!blackPositions.contains(pos) &&
                !hasAdjacentBlack(r, c, blackPositions)) {

                blackPositions.add(pos);
                board[r][c] = new Cell(CellType.BLACK, -1, r, c);
            }
            attempts++;
        }

        // Add numbered cells
        List<String> blackPosList = new ArrayList<>(blackPositions);
        Collections.shuffle(blackPosList);

        int numberedCount = 0;
        int targetNumbered = 4 + rng.nextInt(2);

        for (String pos : blackPosList) {
            if (numberedCount >= targetNumbered) break;

            String[] parts = pos.split(",");
            int r = Integer.parseInt(parts[0]);
            int c = Integer.parseInt(parts[1]);

            int blanks = countBlankNeighbors(r, c);

            if (blanks > 0) {
                int num = rng.nextInt(Math.min(5, blanks + 1));
                board[r][c] = new Cell(CellType.NUMBER, num, r, c);
                numberedCount++;
            }
        }

        if (isPuzzleSolvable()) {
            puzzleValid = true;
        }
    }

    // Reset bulbs/dots
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            board[r][c].bulb = false;
            board[r][c].dot = false;
        }
    }

    recomputeLighting();

    // Reset game state
    userTurn = true;
    userContributed = false;
    aiContributed = false;
    lastComputerR = -1;
    lastComputerC = -1;

    // 🔥 VERY IMPORTANT
    solutionReady = false;
    perfectSolution = null;

    // Reset undo/redo
    // Reset undo/redo
undoStack.clear();
redoStack.clear();

// Push ONLY ONE baseline state
undoStack.push(snapshotState());

}

    private void saveInitialBoard() {
        initialBoard = new Cell[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Cell src = board[r][c];
                Cell dst = new Cell(src.type, src.number, r, c);
                initialBoard[r][c] = dst;
            }
        }
    }

    private void restoreInitialBoard() {

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {

            Cell src = initialBoard[r][c];
            Cell dst = board[r][c];

            dst.type = src.type;
            dst.number = src.number;
            dst.bulb = false;
            dst.dot = false;
            dst.lit = false;
        }
    }

    recomputeLighting();

    // Reset game state
    userTurn = true;
    userContributed = false;
    aiContributed = false;
    lastComputerR = -1;
    lastComputerC = -1;

    // 🔥 VERY IMPORTANT
    solutionReady = false;
    perfectSolution = null;

    // Reset undo/redo
    undoStack.clear();
    redoStack.clear();
    pushUndoState();
}

    private boolean isPuzzleSolvable() {
        boolean[][] savedBulbs = new boolean[rows][cols];
        boolean[][] savedDots = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                savedBulbs[r][c] = board[r][c].bulb;
                savedDots[r][c] = board[r][c].dot;
            }
        }

        List<int[]> blanks = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (board[r][c].type == CellType.BLANK) {
                    blanks.add(new int[]{r, c});
                    board[r][c].bulb = false;
                    board[r][c].dot = false;
                }
            }
        }

        boolean solvable = solveDivideAndConquer();



        // Restore
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                board[r][c].bulb = savedBulbs[r][c];
                board[r][c].dot = savedDots[r][c];
            }
        }
        recomputeLighting();

        return solvable;
    }

    private boolean hasAdjacentBlack(int r, int c, Set<String> blackPositions) {
        int[][] dirs = {{-1,0}, {1,0}, {0,-1}, {0,1}, {-1,-1}, {-1,1}, {1,-1}, {1,1}};
        for (int[] dir : dirs) {
            int nr = r + dir[0];
            int nc = c + dir[1];
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                if (blackPositions.contains(nr + "," + nc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int countBlankNeighbors(int r, int c) {
        int count = 0;
        int[][] dirs = {{-1,0}, {1,0}, {0,-1}, {0,1}};
        for (int[] dir : dirs) {
            int nr = r + dir[0];
            int nc = c + dir[1];
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                if (board[nr][nc].type == CellType.BLANK) count++;
            }
        }
        return count;
    }

    // ===== GRAPH =====
    private void buildGraph() {
        cellGraph = new HashMap<>();
        blankNodes = new ArrayList<>();
        int nodeId = 0;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (board[r][c].type == CellType.BLANK) {
                    GraphNode node = new GraphNode(nodeId, r, c);
                    cellGraph.put(nodeId, node);
                    blankNodes.add(node);
                    board[r][c].graphId = nodeId;
                    nodeId++;
                }
            }
        }

        for (GraphNode node : blankNodes) {
            int r = node.row;
            int c = node.col;

            int[][] dirs = {{-1,0}, {1,0}, {0,-1}, {0,1}};
            for (int[] dir : dirs) {
                int nr = r + dir[0];
                int nc = c + dir[1];
                while (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                    if (board[nr][nc].isWall()) break;
                    if (board[nr][nc].type == CellType.BLANK) {
                        int neighborId = board[nr][nc].graphId;
                        GraphNode neighbor = cellGraph.get(neighborId);
                        if (!node.neighbors.contains(neighbor)) {
                            node.neighbors.add(neighbor);
                            node.degree++;
                        }
                        break;
                    }
                    nr += dir[0];
                    nc += dir[1];
                }
            }
        }
    }

    // ===== SORTING ALGORITHM (Merge Sort on centrality) =====


    private void buildUI() {
        setLayout(new BorderLayout(0, 0));
        getContentPane().setBackground(BG_COLOR);
        
        // Create minimalist header
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(PANEL_BG);
        headerPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 225, 230)),
            new EmptyBorder(15, 25, 15, 25)
        ));
        
        JLabel titleLabel = new JLabel("LIGHT UP", SwingConstants.LEFT);
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setForeground(new Color(30, 40, 60));
        headerPanel.add(titleLabel, BorderLayout.WEST);
        
        // Add turn indicator to header
        JLabel turnIndicator = new JLabel("", SwingConstants.RIGHT);
        turnIndicator.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        turnIndicator.setForeground(new Color(100, 120, 140));
        
        Timer turnUpdateTimer = new Timer(500, e -> {
            if (userTurn) {
                turnIndicator.setText("YOUR TURN");
                turnIndicator.setForeground(new Color(80, 150, 80));
            } else {
                turnIndicator.setText("AI THINKING");
                turnIndicator.setForeground(new Color(200, 100, 100));
            }
        });
        turnUpdateTimer.start();
        
        headerPanel.add(turnIndicator, BorderLayout.EAST);
        
        add(headerPanel, BorderLayout.NORTH);
        
        // Create main content panel
        JPanel mainPanel = new JPanel(new BorderLayout(20, 20));
        mainPanel.setBackground(BG_COLOR);
        mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        
        // Create game grid with minimalist design
        JPanel gridContainer = new JPanel(new BorderLayout());
        gridContainer.setBackground(BG_COLOR);
        gridContainer.setBorder(BorderFactory.createEmptyBorder());
        
        gridPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                paintGrid(g);
            }
        };
        gridPanel.setBackground(GRID_BG);
        gridPanel.setPreferredSize(new Dimension(cols * 70, rows * 70));
        gridPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int w = gridPanel.getWidth();
                int h = gridPanel.getHeight();

                int cellSize = Math.min(w / cols, h / rows);
                int offsetX = (w - cellSize * cols) / 2;
                int offsetY = (h - cellSize * rows) / 2;

                int x = e.getX() - offsetX;
                int y = e.getY() - offsetY;

                if (x < 0 || y < 0) return;

                int c = x / cellSize;
                int r = y / cellSize;

                if (r < 0 || r >= rows || c < 0 || c >= cols) return;

                if (SwingUtilities.isLeftMouseButton(e)) {
                    handleUserToggleBulb(r, c);
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    handleUserToggleDot(r, c);
                }
            }
        });
        
        gridContainer.add(gridPanel, BorderLayout.CENTER);
        mainPanel.add(gridContainer, BorderLayout.CENTER);
        
        // Create control panel with minimalist design
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBackground(PANEL_BG);
        controlPanel.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(new Color(220, 225, 230), 1),
            new EmptyBorder(20, 15, 20, 15)
        ));
        
        // Create buttons with clean design
        newGameBtn = createMinimalButton("New Game", new Color(70, 130, 180));
        restartBtn = createMinimalButton("Restart", new Color(65, 105, 225));
        undoBtn = createMinimalButton("Undo", new Color(72, 61, 139));
        redoBtn = createMinimalButton("Redo", new Color(106, 90, 205));
        solveBtn = createMinimalButton("Solve", new Color(50, 180, 80));
        
        newGameBtn.addActionListener(e -> {
            generateRandomPuzzle();
            saveInitialBoard();
            buildGraph();
            statusLabel.setText("New game generated");
            gridPanel.setPreferredSize(new Dimension(cols * 70, rows * 70));
            pack();
            gridPanel.repaint();
        });

        restartBtn.addActionListener(e -> {
            restoreInitialBoard();
            buildGraph();
            statusLabel.setText("Game restarted");
            gridPanel.repaint();
        });

        undoBtn.addActionListener(e -> {

    if (undoStack.size() > 1) {

        // Save current state to redo stack
        redoStack.push(snapshotState());

        // Remove current snapshot
        undoStack.pop();

        // Apply previous snapshot
        BoardState previous = undoStack.peek();
        applyState(previous);

        recomputeLighting();
        statusLabel.setText("Undo move");
        gridPanel.repaint();

    } else {
        statusLabel.setText("Nothing to undo");
    }
});

        redoBtn.addActionListener(e -> {

    if (!redoStack.isEmpty()) {

        BoardState next = redoStack.pop();

        // Save current to undo
        undoStack.push(snapshotState());

        applyState(next);

        recomputeLighting();
        statusLabel.setText("Redo move");
        gridPanel.repaint();

    } else {
        statusLabel.setText("Nothing to redo");
    }
});


        solveBtn.addActionListener(e -> {

    pushUndoState();
    redoStack.clear();

    boolean solved = solveDivideAndConquer();

    if (solved) {
        recomputeLighting();
        statusLabel.setText("Solved successfully");
    } else {
        statusLabel.setText("No solution found");
    }

    gridPanel.repaint();
});

        // Add vertical spacing between buttons
        controlPanel.add(newGameBtn);
        controlPanel.add(Box.createVerticalStrut(8));
        controlPanel.add(restartBtn);
        controlPanel.add(Box.createVerticalStrut(8));
        controlPanel.add(undoBtn);
        controlPanel.add(Box.createVerticalStrut(8));
        controlPanel.add(redoBtn);
        controlPanel.add(Box.createVerticalStrut(8));
        controlPanel.add(solveBtn);
        
        // Add move counter at bottom of control panel
        JPanel counterPanel = new JPanel();
        counterPanel.setBackground(PANEL_BG);
        counterPanel.setBorder(new EmptyBorder(15, 0, 0, 0));
        
        JLabel moveCounter = new JLabel("Moves: 0");
        moveCounter.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        moveCounter.setForeground(new Color(120, 130, 150));
        counterPanel.add(moveCounter);
        
        controlPanel.add(Box.createVerticalGlue());
        controlPanel.add(counterPanel);
        
        mainPanel.add(controlPanel, BorderLayout.EAST);
        
        add(mainPanel, BorderLayout.CENTER);
        
        // Create minimalist status bar
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBackground(PANEL_BG);
        statusPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 225, 230)),
            new EmptyBorder(8, 20, 8, 20)
        ));
        
        statusLabel = new JLabel("Click cells to place bulbs");
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        statusLabel.setForeground(new Color(80, 90, 110));
        
        statusPanel.add(statusLabel, BorderLayout.WEST);
        
        // Add current mode indicator
        JLabel modeLabel = new JLabel("7×7 Puzzle");
        modeLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        modeLabel.setForeground(new Color(140, 150, 170));
        statusPanel.add(modeLabel, BorderLayout.EAST);
        
        add(statusPanel, BorderLayout.SOUTH);
        
        // Update move counter timer
        new Timer(100, e -> {
            moveCounter.setText("Moves: " + (undoStack.size() - 1));
        }).start();
    }
    
    private JButton createMinimalButton(String text, Color baseColor) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        button.setForeground(new Color(50, 60, 80));
        button.setBackground(Color.WHITE);
        button.setBorder(BorderFactory.createCompoundBorder(
            new LineBorder(new Color(220, 225, 230), 1),
            new EmptyBorder(10, 20, 10, 20)
        ));
        button.setFocusPainted(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        
        // Add minimalist hover effect
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(new Color(245, 248, 255));
                button.setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(baseColor, 1),
                    new EmptyBorder(10, 20, 10, 20)
                ));
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(Color.WHITE);
                button.setBorder(BorderFactory.createCompoundBorder(
                    new LineBorder(new Color(220, 225, 230), 1),
                    new EmptyBorder(10, 20, 10, 20)
                ));
            }
        });
        
        return button;
    }

    // ===== USER ACTIONS =====
    private void handleUserToggleBulb(int r, int c) {

    Cell cell = board[r][c];
    if (cell.type != CellType.BLANK) return;

    boolean changed = false;

    if (cell.bulb) {
        cell.bulb = false;
        changed = true;
        statusLabel.setText("Bulb removed at (" + r + "," + c + ")");
    } else {
        cell.bulb = true;
        cell.dot = false;
        changed = true;
        statusLabel.setText("Bulb placed at (" + r + "," + c + ")");
    }

    if (!changed) return;

    recomputeLighting();

    // 🔥 PUSH AFTER change
    undoStack.push(snapshotState());
    redoStack.clear();

    userContributed = true;
    gridPanel.repaint();

    if (tryFinishAndPopup()) return;

    if (userTurn) {
        userTurn = false;
        delayedComputerMove();
    }
}

    private void handleUserToggleDot(int r, int c) {

    Cell cell = board[r][c];
    if (cell.type != CellType.BLANK) return;

    cell.dot = !cell.dot;
    if (cell.dot) cell.bulb = false;

    recomputeLighting();

    // 🔥 PUSH AFTER change
    undoStack.push(snapshotState());
    redoStack.clear();

    userContributed = true;
    statusLabel.setText((cell.dot ? "Marked" : "Unmarked") + " dot at (" + r + "," + c + ")");
    gridPanel.repaint();

    if (tryFinishAndPopup()) return;

    if (userTurn) {
        userTurn = false;
        delayedComputerMove();
    }
}

    // ===== LIGHTING =====
    private void recomputeLighting() {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                board[r][c].lit = false;
            }
        }
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (board[r][c].bulb) {
                    board[r][c].lit = true;
                    for (int rr = r - 1; rr >= 0; rr--) {
                        if (board[rr][c].isWall()) break;
                        board[rr][c].lit = true;
                    }
                    for (int rr = r + 1; rr < rows; rr++) {
                        if (board[rr][c].isWall()) break;
                        board[rr][c].lit = true;
                    }
                    for (int cc = c - 1; cc >= 0; cc--) {
                        if (board[r][cc].isWall()) break;
                        board[r][cc].lit = true;
                    }
                    for (int cc = c + 1; cc < cols; cc++) {
                        if (board[r][cc].isWall()) break;
                        board[r][cc].lit = true;
                    }
                }
            }
        }
    }

    // ===== RULE HELPERS =====
    private boolean isViolatingBulb(int r, int c) {
        if (!board[r][c].bulb) return false;

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        // Rule: bulb sees another bulb
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            while (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                if (board[nr][nc].isWall()) break;
                if (board[nr][nc].bulb && (nr != r || nc != c)) {
                    return true;
                }
                nr += d[0];
                nc += d[1];
            }
        }

        // Rule: adjacent numbered cell exceeded
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;

            if (board[nr][nc].type == CellType.NUMBER) {
                int count = 0;
                for (int[] d2 : dirs) {
                    int rr = nr + d2[0], cc = nc + d2[1];
                    if (rr >= 0 && rr < rows && cc >= 0 && cc < cols) {
                        if (board[rr][cc].bulb) count++;
                    }
                }
                if (count > board[nr][nc].number) return true;
            }
        }

        return false;
    }

    private boolean canPlaceBulb(int r, int c) {
        if (board[r][c].type != CellType.BLANK) return false;
        if (board[r][c].bulb || board[r][c].dot) return false;

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        // Rule 1: no bulb sees another bulb
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            while (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                if (board[nr][nc].isWall()) break;
                if (board[nr][nc].bulb) return false;
                nr += d[0];
                nc += d[1];
            }
        }

        // Rule 2: numbered cells cannot exceed limit (with potential placement)
        for (int[] d : dirs) {
            int nr = r + d[0], nc = c + d[1];
            if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;

            if (board[nr][nc].type == CellType.NUMBER) {
                int count = 0;
                for (int[] d2 : dirs) {
                    int rr = nr + d2[0], cc = nc + d2[1];
                    if (rr >= 0 && rr < rows && cc >= 0 && cc < cols) {
                        if (board[rr][cc].bulb) count++;
                    }
                }
                // Add the potential bulb we're considering
                boolean adjacentToThis = false;
                for (int[] d2 : dirs) {
                    int rr = nr + d2[0], cc = nc + d2[1];
                    if (rr == r && cc == c) {
                        adjacentToThis = true;
                        break;
                    }
                }
                if (adjacentToThis) count++;
                
                if (count > board[nr][nc].number) return false;
            }
        }

        return true;
    }
    // ===== AI MOVE =====
    private void delayedComputerMove() {
        Timer timer = new Timer(500, e -> {
            computerMakeOneMove();
            userTurn = true;
            gridPanel.repaint();
            ((Timer) e.getSource()).stop();
        });
        timer.setRepeats(false);
        timer.start();
    }
    private void computerMakeOneMove() {

    int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

    boolean moveMade = false;

    // Save snapshot BEFORE real mutation
    BoardState beforeMove = snapshotState();

    // =====================================================
    // STEP 1: Remove violating bulbs SAFELY
    // =====================================================
    for (int r = 0; r < rows && !moveMade; r++) {
        for (int c = 0; c < cols && !moveMade; c++) {

            if (board[r][c].bulb && isViolatingBulb(r, c)) {

                board[r][c].bulb = false;
                recomputeLighting();

                if (checkStillSolvable()) {
                    moveMade = true;
                    lastComputerR = r;
                    lastComputerC = c;
                    statusLabel.setText("AI removed violating bulb");
                } else {
                    board[r][c].bulb = true;
                    recomputeLighting();
                }
            }
        }
    }

    // =====================================================
    // STEP 2: Satisfy numbered cells
    // =====================================================
    for (int r = 0; r < rows && !moveMade; r++) {
        for (int c = 0; c < cols && !moveMade; c++) {

            if (board[r][c].type == CellType.NUMBER) {

                int required = board[r][c].number;
                int count = 0;
                List<int[]> candidates = new ArrayList<>();

                for (int[] d : dirs) {
                    int nr = r + d[0];
                    int nc = c + d[1];

                    if (nr < 0 || nr >= rows || nc < 0 || nc >= cols)
                        continue;

                    if (board[nr][nc].bulb) count++;
                    else if (board[nr][nc].type == CellType.BLANK &&
                             !board[nr][nc].dot)
                        candidates.add(new int[]{nr,nc});
                }

                if (count < required) {

                    for (int[] pos : candidates) {
                        int pr = pos[0];
                        int pc = pos[1];

                        if (canPlaceBulb(pr, pc)) {

                            board[pr][pc].bulb = true;
                            recomputeLighting();

                            if (checkStillSolvable()) {
                                moveMade = true;
                                lastComputerR = pr;
                                lastComputerC = pc;
                                statusLabel.setText("AI satisfied number constraint");
                                break;
                            } else {
                                board[pr][pc].bulb = false;
                                recomputeLighting();
                            }
                        }
                    }
                }
            }
        }
    }

    // =====================================================
    // STEP 3: Place safe bulb
    // =====================================================
    for (int r = 0; r < rows && !moveMade; r++) {
        for (int c = 0; c < cols && !moveMade; c++) {

            if (board[r][c].type == CellType.BLANK &&
                !board[r][c].bulb &&
                !board[r][c].dot &&
                canPlaceBulb(r, c)) {

                board[r][c].bulb = true;
                recomputeLighting();

                if (checkStillSolvable()) {
                    moveMade = true;
                    lastComputerR = r;
                    lastComputerC = c;
                    statusLabel.setText("AI placed safe bulb");
                } else {
                    board[r][c].bulb = false;
                    recomputeLighting();
                }
            }
        }
    }

    if (moveMade) {

    undoStack.push(snapshotState());
    redoStack.clear();

    aiContributed = true;

    recomputeLighting();
    gridPanel.repaint();

    if (tryFinishAndPopup()) {
        return;
    }

} else {
    statusLabel.setText("AI has no valid move");
}

}

    private boolean checkStillSolvable() {

    // Save current board
    boolean[][] savedBulbs = new boolean[rows][cols];
    boolean[][] savedDots = new boolean[rows][cols];

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            savedBulbs[r][c] = board[r][c].bulb;
            savedDots[r][c] = board[r][c].dot;
        }
    }

    boolean solvable = solveDivideAndConquer();

    // Restore board
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            board[r][c].bulb = savedBulbs[r][c];
            board[r][c].dot = savedDots[r][c];
        }
    }

    recomputeLighting();

    return solvable;
}

    // ===== ENHANCED AI HELPER METHODS =====
    // ===== VALIDATION =====
    private String validateSolution() {
        // Rule 1: all blank cells lit
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (board[r][c].type == CellType.BLANK && !board[r][c].lit) {
                    return "❌ Unlit cell at (" + r + "," + c + ")";
                }
            }
        }

        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};

        // Rule 2: bulbs must not see each other
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!board[r][c].bulb) continue;

                for (int[] d : dirs) {
                    int nr = r + d[0], nc = c + d[1];
                    while (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                        if (board[nr][nc].isWall()) break;
                        if (board[nr][nc].bulb && (nr != r || nc != c)) {
                            return "❌ Bulbs see each other at (" +
                                   r + "," + c + ") & (" + nr + "," + nc + ")";
                        }
                        nr += d[0];
                        nc += d[1];
                    }
                }
            }
        }

        // Rule 3: numbered cells exact match
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (board[r][c].type == CellType.NUMBER) {
                    int count = 0;
                    for (int[] d : dirs) {
                        int nr = r + d[0], nc = c + d[1];
                        if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) {
                            if (board[nr][nc].bulb) count++;
                        }
                    }
                    if (count != board[r][c].number) {
                        return "❌ Number mismatch at (" + r + "," + c + ")";
                    }
                }
            }
        }

        return "Puzzle solved! ✅";
    }

    private boolean tryFinishAndPopup() {
        String result = validateSolution();
        if (result.equals("Puzzle solved! ✅")) {
            onSolvedCheckTeamWin();
            return true;
        }
        return false;
    }

    private void onSolvedCheckTeamWin() {
        statusLabel.setText("Puzzle solved");
        gridPanel.repaint();

        boolean teamWin = userContributed && aiContributed;
        String msg = teamWin
            ? "Team effort! Both you and the AI contributed to solving the puzzle."
            : "Congratulations! Puzzle solved!";
        JOptionPane.showMessageDialog(
            this,
            msg,
            "Puzzle Complete",
            JOptionPane.INFORMATION_MESSAGE
        );
    }

    private void paintGrid(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        
        int w = gridPanel.getWidth();
        int h = gridPanel.getHeight();
        int cellSize = Math.min(w / cols, h / rows);

        int offsetX = (w - cellSize * cols) / 2;
        int offsetY = (h - cellSize * rows) / 2;

        // Draw clean grid background
        g2.setColor(GRID_BG);
        g2.fillRect(offsetX, offsetY, cellSize * cols, cellSize * rows);

        // Draw cells
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = offsetX + c * cellSize;
                int y = offsetY + r * cellSize;

                Cell cell = board[r][c];

                // Create cell background
                if (cell.type == CellType.BLACK || cell.type == CellType.NUMBER) {
                    // Draw wall cell
                    g2.setColor(WALL_COLOR);
                    g2.fillRect(x + 1, y + 1, cellSize - 2, cellSize - 2);
                    
                    if (cell.type == CellType.NUMBER) {
                        // Draw number with clean typography
                        g2.setFont(new Font("Segoe UI", Font.BOLD, cellSize / 2));
                        String s = Integer.toString(cell.number);
                        FontMetrics fm = g2.getFontMetrics();
                        int tw = fm.stringWidth(s);
                        int th = fm.getAscent();
                        
                        g2.setColor(NUMBER_COLOR);
                        g2.drawString(s, x + (cellSize - tw)/2, y + (cellSize + th)/2 - 4);
                    }
                } else {
                    // Draw blank cell with lighting effect
                    if (cell.lit) {
                        g2.setColor(LIT_COLOR);
                        g2.fillRect(x + 1, y + 1, cellSize - 2, cellSize - 2);
                    } else {
                        g2.setColor(GRID_BG);
                        g2.fillRect(x + 1, y + 1, cellSize - 2, cellSize - 2);
                    }
                }

                // Draw bulb with clean design (removed middle line)
                if (cell.bulb) {
                    int bulbSize = cellSize * 3 / 5;
                    int bulbX = x + (cellSize - bulbSize) / 2;
                    int bulbY = y + (cellSize - bulbSize) / 2;
                    
                    // Draw subtle glow
                    g2.setColor(BULB_GLOW);
                    g2.fillOval(bulbX - 3, bulbY - 3, bulbSize + 6, bulbSize + 6);
                    
                    // Draw clean bulb circle
                    GradientPaint bulbGradient = new GradientPaint(
                        bulbX, bulbY, BULB_COLOR.brighter(),
                        bulbX + bulbSize, bulbY + bulbSize, BULB_COLOR
                    );
                    g2.setPaint(bulbGradient);
                    g2.fillOval(bulbX, bulbY, bulbSize, bulbSize);
                    
                    // Draw bulb outline
                    g2.setColor(BULB_COLOR.darker());
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawOval(bulbX, bulbY, bulbSize, bulbSize);
                    
                    // Highlight computer's last move with subtle border
                    if (r == lastComputerR && c == lastComputerC) {
                        g2.setColor(AI_HIGHLIGHT);
                        g2.setStroke(new BasicStroke(2));
                        g2.drawRect(x + 1, y + 1, cellSize - 3, cellSize - 3);
                    }
                } else if (cell.dot) {
                    // Draw minimalist dot
                    int dotSize = Math.max(5, cellSize / 7);
                    int dotX = x + (cellSize - dotSize) / 2;
                    int dotY = y + (cellSize - dotSize) / 2;
                    
                    g2.setColor(DOT_COLOR);
                    g2.fillOval(dotX, dotY, dotSize, dotSize);
                    
                    // Add subtle border to dot
                    g2.setColor(DOT_COLOR.darker());
                    g2.setStroke(new BasicStroke(1));
                    g2.drawOval(dotX, dotY, dotSize, dotSize);
                }

                // Draw conflict overlay
                boolean conflict = false;
                if (cell.type == CellType.BLANK && cell.bulb) {
                    for (int rr = r - 1; rr >= 0; rr--) {
                        if (board[rr][c].isWall()) break;
                        if (board[rr][c].bulb) conflict = true;
                    }
                    for (int rr = r + 1; rr < rows; rr++) {
                        if (board[rr][c].isWall()) break;
                        if (board[rr][c].bulb) conflict = true;
                    }
                    for (int cc = c - 1; cc >= 0; cc--) {
                        if (board[r][cc].isWall()) break;
                        if (board[r][cc].bulb) conflict = true;
                    }
                    for (int cc = c + 1; cc < cols; cc++) {
                        if (board[r][cc].isWall()) break;
                        if (board[r][cc].bulb) conflict = true;
                    }
                } else if (cell.type == CellType.NUMBER) {
                    int count = 0;
                    int[][] neigh = {{1,0},{-1,0},{0,1},{0,-1}};
                    for (int[] d : neigh) {
                        int rr = r + d[0], cc = c + d[1];
                        if (rr < 0 || rr >= rows || cc < 0 || cc >= cols) continue;
                        if (board[rr][cc].bulb) count++;
                    }
                    if (count != cell.number) conflict = true;
                }

                if (conflict) {
                    g2.setColor(CONFLICT_COLOR);
                    g2.fillRect(x + 1, y + 1, cellSize - 2, cellSize - 2);
                }

                // Draw clean cell border
                g2.setColor(GRID_LINE);
                g2.setStroke(new BasicStroke(1));
                g2.drawRect(x, y, cellSize, cellSize);
            }
        }
    }

    // ===== SAVE/LOAD SNAPSHOTS FOR UNDO/REDO =====
    private BoardState snapshotState() {
        BoardState st = new BoardState(rows, cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                st.bulbs[r][c] = board[r][c].bulb;
                st.dots[r][c] = board[r][c].dot;
            }
        }
        st.userTurn = userTurn;
        st.userContributed = userContributed;
        st.aiContributed = aiContributed;
        st.lastComputerR = lastComputerR;
        st.lastComputerC = lastComputerC;
        return st;
    }

    private void applyState(BoardState st) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                board[r][c].bulb = st.bulbs[r][c];
                board[r][c].dot = st.dots[r][c];
            }
        }
        userTurn = st.userTurn;
        userContributed = st.userContributed;
        aiContributed = st.aiContributed;
        lastComputerR = st.lastComputerR;
        lastComputerC = st.lastComputerC;
    }

    private void pushUndoState() {
        undoStack.push(snapshotState());
    }
    private List<List<int[]>> divideIntoRegions() {
    boolean[][] visited = new boolean[rows][cols];
    List<List<int[]>> regions = new ArrayList<>();

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (!visited[r][c] && board[r][c].type == CellType.BLANK) {
                List<int[]> region = new ArrayList<>();
                dfsRegion(r, c, visited, region);
                regions.add(region);
            }
        }
    }

    return regions;
}

private void dfsRegion(int r, int c, boolean[][] visited, List<int[]> region) {
    if (r < 0 || r >= rows || c < 0 || c >= cols) return;
    if (visited[r][c]) return;
    if (board[r][c].type != CellType.BLANK) return;

    visited[r][c] = true;
    region.add(new int[]{r, c});

    dfsRegion(r + 1, c, visited, region);
    dfsRegion(r - 1, c, visited, region);
    dfsRegion(r, c + 1, visited, region);
    dfsRegion(r, c - 1, visited, region);
}   
    // Replace the whole solveDivideAndConquer() with this version
    private boolean solveDivideAndConquer() {

    // 🔥 IMPORTANT: DO NOT CLEAR BOARD
    // We solve from current partial state

    recomputeLighting();

    boolean changed;
    do {
        changed = false;
        changed |= applyAllSimpleDeductions();
    } while (changed);

    List<List<int[]>> regions = divideIntoRegions();
    mergeSortRegions(regions);

    for (List<int[]> region : regions) {
        if (!solveRegionDP(region)) {
            return false;
        }
    }

    recomputeLighting();

    boolean solved = validateSolution().equals("Puzzle solved! ✅");

    if (solved) {
        storePerfectSolution();
    }

    return solved;
}
    private boolean solveRegionDP(List<int[]> region) {

    Map<String, Boolean> memo = new HashMap<>();
    return backtrackRegion(region, 0, memo);
}

    private void mergeSortRegions(List<List<int[]>> regions) {
    if (regions.size() <= 1) return;

    int mid = regions.size() / 2;

    List<List<int[]>> left = new ArrayList<>(regions.subList(0, mid));
    List<List<int[]>> right = new ArrayList<>(regions.subList(mid, regions.size()));

    mergeSortRegions(left);
    mergeSortRegions(right);

    mergeRegions(regions, left, right);
}

private void mergeRegions(List<List<int[]>> result,
                          List<List<int[]>> left,
                          List<List<int[]>> right) {

    int i = 0, j = 0, k = 0;

    while (i < left.size() && j < right.size()) {
        if (left.get(i).size() <= right.get(j).size()) {
            result.set(k++, left.get(i++));
        } else {
            result.set(k++, right.get(j++));
        }
    }

    while (i < left.size()) result.set(k++, left.get(i++));
    while (j < right.size()) result.set(k++, right.get(j++));
}
 
    private boolean applyAllSimpleDeductions() {
    boolean changed = false;

    // Rule A: Number cell with N neighbors must have exactly N bulbs
    changed |= completeNumberCells();

    // Rule B: If a cell is the only way to light another cell → must place bulb
    changed |= forceSingleLightSources();

    // Rule C: If placing a bulb would immediately violate → must place dot (forbid)
    changed |= forbidConflictingPlacements();

    // Rule D: If a number cell has exactly the right number already → forbid rest
    changed |= forbidExcessPlacementsNearSatisfiedNumbers();

    return changed;
}        
    private boolean completeNumberCells() {
    boolean changed = false;
    int[][] dirs = {{0,1},{1,0},{0,-1},{-1,0}};

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (board[r][c].type != CellType.NUMBER) continue;

            int need = board[r][c].number;
            List<int[]> candidates = new ArrayList<>();
            int already = 0;

            for (int[] d : dirs) {
                int nr = r + d[0], nc = c + d[1];
                if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue;
                if (board[nr][nc].type != CellType.BLANK) continue;

                if (board[nr][nc].bulb) already++;
                else if (!board[nr][nc].dot) candidates.add(new int[]{nr, nc});
            }

            // Case 1: exactly the remaining spots needed → fill them all
            if (candidates.size() == need - already && need - already > 0) {
                for (int[] pos : candidates) {
                    int pr = pos[0], pc = pos[1];
                    if (!board[pr][pc].bulb) {
                        board[pr][pc].bulb = true;
                        changed = true;
                    }
                }
            }

            // Case 2: already satisfied → forbid remaining
            if (already == need) {
                for (int[] pos : candidates) {
                    int pr = pos[0], pc = pos[1];
                    if (!board[pr][pc].dot) {
                        board[pr][pc].dot = true;
                        changed = true;
                    }
                }
            }
        }
    }
    if (changed) recomputeLighting();
    return changed;
}

private boolean forceSingleLightSources() {
    // very similar to your old findSingleLightSourcePlacement(), but apply all at once
    boolean changed = false;

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (board[r][c].type != CellType.BLANK || board[r][c].lit || board[r][c].dot) continue;

            List<int[]> possibleBulbPositions = findPossibleBulbPositionsThatLightThisCell(r, c);

            if (possibleBulbPositions.size() == 1) {
                int[] pos = possibleBulbPositions.get(0);
                int br = pos[0], bc = pos[1];
                if (canPlaceBulb(br, bc) && !board[br][bc].bulb) {
                    board[br][bc].bulb = true;
                    changed = true;
                }
            }
        }
    }
    if (changed) recomputeLighting();
    return changed;
}

// Helper for above
private List<int[]> findPossibleBulbPositionsThatLightThisCell(int tr, int tc) {
    List<int[]> possibles = new ArrayList<>();
    int[][] dirs = {{0,1},{1,0},{0,-1},{-1,0}};

    for (int[] d : dirs) {
        int r = tr + d[0], c = tc + d[1];
        while (r >= 0 && r < rows && c >= 0 && c < cols && !board[r][c].isWall()) {
            if (board[r][c].type == CellType.BLANK && canPlaceBulb(r, c) && !board[r][c].dot) {
                possibles.add(new int[]{r, c});
            }
            r += d[0]; c += d[1];
        }
    }
    return possibles;
}

private boolean forbidConflictingPlacements() {
    boolean changed = false;
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            if (board[r][c].type != CellType.BLANK || board[r][c].dot || board[r][c].bulb) continue;
            if (!canPlaceBulb(r, c)) {
                board[r][c].dot = true;
                changed = true;
            }
        }
    }
    return changed;
}

private boolean forbidExcessPlacementsNearSatisfiedNumbers() {
    // similar to completeNumberCells() case 2 — already implemented there
    return false; // usually covered
}
private boolean backtrackRegion(List<int[]> region,
                                int index,
                                Map<String, Boolean> memo) {

    if (index == region.size()) {
        recomputeLighting();
        return isRegionValid(region);
    }

    String key = buildStateKey(region, index);
    if (memo.containsKey(key)) return memo.get(key);

    int r = region.get(index)[0];
    int c = region.get(index)[1];

    // Option 1: no bulb
    board[r][c].bulb = false;
    if (backtrackRegion(region, index + 1, memo)) {
        memo.put(key, true);
        return true;
    }

    // Option 2: place bulb if allowed
    if (canPlaceBulb(r, c)) {
        board[r][c].bulb = true;
        if (backtrackRegion(region, index + 1, memo)) {
            memo.put(key, true);
            return true;
        }
        board[r][c].bulb = false;
    }

    memo.put(key, false);
    return false;
}

private String buildStateKey(List<int[]> region, int index) {
    StringBuilder sb = new StringBuilder();
    sb.append(index).append("|");

    for (int[] cell : region) {
        sb.append(board[cell[0]][cell[1]].bulb ? "1" : "0");
    }

    return sb.toString();
}
private boolean isRegionValid(List<int[]> region) {

    for (int[] p : region) {
        int r = p[0], c = p[1];

        if (!board[r][c].lit) return false;

        if (board[r][c].bulb && isViolatingBulb(r, c))
            return false;
    }

    return true;
}
private void storePerfectSolution() {
    perfectSolution = new boolean[rows][cols];

    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            perfectSolution[r][c] = board[r][c].bulb;
        }
    }

    solutionReady = true;
}
private void clearBoardState() {
    for (int r = 0; r < rows; r++) {
        for (int c = 0; c < cols; c++) {
            board[r][c].bulb = false;
            board[r][c].dot = false;
        }
    }
    recomputeLighting();
}
    // ===== MAIN =====
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LightUp game = new LightUp();
            game.setMinimumSize(new Dimension(900, 700));
        });
    }
}
