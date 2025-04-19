import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.Timer;
import java.util.TimerTask;

public class ScheduleManagerApp {
    static final String DB_URL = "jdbc:sqlite:schedule.db";
    static DefaultTableModel tableModel;
    static JTable table;

    public static void main(String[] args) {
        createDatabase();

        JFrame frame = new JFrame("Schedule Manager");
        frame.setSize(600, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // Top Panel - Add Schedule
        JPanel topPanel = new JPanel(new GridLayout(3, 2));
        JTextField titleField = new JTextField();
        JTextArea detailArea = new JTextArea(3, 20);
        JButton addBtn = new JButton("Add Schedule");
        Font font = new Font("Arial", Font.PLAIN, 32); // Adjust the size here if needed

        // Set the font for the text fields and text area
        titleField.setFont(font);
        detailArea.setFont(font);

        topPanel.add(new JLabel("Title:"));
        topPanel.add(titleField);
        topPanel.add(new JLabel("Details:"));
        topPanel.add(new JScrollPane(detailArea));
        topPanel.add(addBtn);

        frame.add(topPanel, BorderLayout.NORTH);

        // Table - History
        tableModel = new DefaultTableModel(new String[] { "ID", "Title", "Details", "Timestamp" }, 0);
        table = new JTable(tableModel);
        Font tableFont = new Font("Arial", Font.PLAIN, 20); // Adjust the font size here
        table.setFont(tableFont);

        // Optionally, set the row height to make it more readable
        table.setRowHeight(30);
        refreshTable();

        frame.add(new JScrollPane(table), BorderLayout.CENTER);

        // Bottom Panel - Actions
        JPanel bottomPanel = new JPanel();
        JButton deleteBtn = new JButton("Delete Selected");
        JButton clearBtn = new JButton("Clear History");
        JButton timerBtn = new JButton("Start Timer");
        JTextField timerField = new JTextField(5);

        bottomPanel.add(deleteBtn);
        bottomPanel.add(clearBtn);
        bottomPanel.add(new JLabel("Timer (sec):"));
        bottomPanel.add(timerField);
        bottomPanel.add(timerBtn);

        frame.add(bottomPanel, BorderLayout.SOUTH);

        // Add Button Action
        addBtn.addActionListener(e -> {
            String title = titleField.getText().trim();
            String detail = detailArea.getText().trim();
            if (!title.isEmpty()) {
                addSchedule(title, detail);
                titleField.setText("");
                detailArea.setText("");
                refreshTable();
            }
        });

        // Delete Button Action
        deleteBtn.addActionListener(e -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                int id = (int) tableModel.getValueAt(selectedRow, 0);
                deleteSchedule(id);
                refreshTable();
            }
        });

        // Clear History Action
        clearBtn.addActionListener(e -> {
            clearSchedules();
            refreshTable();
        });

        // Timer Button Action
        timerBtn.addActionListener(e -> {
            try {
                int seconds = Integer.parseInt(timerField.getText().trim());
                startCountdown(seconds);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Enter valid seconds.");
            }
        });

        frame.setVisible(true);
    }

    // Create Database if it doesn't exist
    static void createDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String createTableSQL = "CREATE TABLE IF NOT EXISTS schedules (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "title TEXT," +
                    "detail TEXT," +
                    "timestamp TEXT)";
            Statement stmt = conn.createStatement();
            stmt.execute(createTableSQL);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Add a new schedule to the database
    static void addSchedule(String title, String detail) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String timestamp = LocalDateTime.now().toString();
            PreparedStatement ps = conn
                    .prepareStatement("INSERT INTO schedules (title, detail, timestamp) VALUES (?, ?, ?)");
            ps.setString(1, title);
            ps.setString(2, detail);
            ps.setString(3, timestamp);
            ps.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Delete a schedule from the database by ID
    static void deleteSchedule(int id) {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            PreparedStatement ps = conn.prepareStatement("DELETE FROM schedules WHERE id = ?");
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Clear all schedules from the database
    static void clearSchedules() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            Statement stmt = conn.createStatement();
            stmt.executeUpdate("DELETE FROM schedules");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Refresh the table to show current schedules
    static void refreshTable() {
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            tableModel.setRowCount(0);
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT * FROM schedules");
            while (rs.next()) {
                tableModel.addRow(new Object[] { rs.getInt("id"), rs.getString("title"), rs.getString("detail"),
                        rs.getString("timestamp") });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Start the countdown timer
    static void startCountdown(int seconds) {
        Timer timer = new Timer();
        timer.schedule(new TimerTask() {
            int timeLeft = seconds;

            public void run() {
                if (timeLeft <= 0) {
                    timer.cancel();
                    JOptionPane.showMessageDialog(null, "hoise vai somoy sesh!");
                }
                timeLeft--;
            }
        }, 0, 1000);
    }
}
