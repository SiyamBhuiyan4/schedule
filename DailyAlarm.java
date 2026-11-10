import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridLayout;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily reminder for macOS. Each entry has a time (HH:mm, 24-hour), the days it rings
 * (1=Mon ... 7=Sun), a title and optional details. At the time, the sound repeats and
 * shows the reminder until you press "Stop Alarm" or 3 minutes pass.
 * Saved in alarms.txt as lines: HH:mm|days|title|details
 */
public class DailyAlarm {
    static final File ALARM_FILE = new File("alarms.txt");
    static final String SOUND = "/System/Library/Sounds/Hero.aiff";
    static final long RING_MS = 3 * 60 * 1000L;   // max ring time: 3 minutes
    static final long GAP_MS = 2000L;              // pause between sound repeats
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");
    static final String ALL_DAYS = "1234567";
    static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};

    static final DefaultListModel<String> model = new DefaultListModel<>();
    static final Map<String, LocalDate> lastFired = new HashMap<>();

    static volatile boolean ringing = false;
    static volatile boolean stopRequested = false;
    static volatile Process player;
    static JDialog alarmDialog;
    static int editIndex = -1;

    public static void main(String[] args) {
        loadAlarms();
        SwingUtilities.invokeLater(DailyAlarm::buildUI);
        new javax.swing.Timer(1000, e -> checkAlarms()).start();
    }

    static void buildUI() {
        JFrame frame = new JFrame("Daily Reminder");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(560, 560);
        frame.setLayout(new BorderLayout(8, 8));

        Font big = new Font("Arial", Font.PLAIN, 18);

        JList<String> list = new JList<>(model);
        list.setFont(big);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                setText(describe((String) value));
                return this;
            }
        });
        frame.add(new JScrollPane(list), BorderLayout.CENTER);

        JTextField timeInput = new JTextField("07:00", 6);
        JTextField titleInput = new JTextField("", 16);
        JTextField detailsInput = new JTextField("", 16);
        timeInput.setFont(big);
        titleInput.setFont(big);
        detailsInput.setFont(big);

        JCheckBox[] dayBoxes = new JCheckBox[7];
        JPanel dayRow = new JPanel(new FlowLayout());
        for (int i = 0; i < 7; i++) {
            dayBoxes[i] = new JCheckBox(DAY_NAMES[i], true);
            dayRow.add(dayBoxes[i]);
        }

        JButton addBtn = new JButton("Add");
        JButton clearBtn = new JButton("Clear Form");
        JButton editBtn = new JButton("Edit Selected");
        JButton deleteBtn = new JButton("Delete Selected");
        JButton testBtn = new JButton("Test Alarm");

        Runnable clearForm = () -> {
            timeInput.setText("");
            titleInput.setText("");
            detailsInput.setText("");
            for (JCheckBox b : dayBoxes) b.setSelected(true);
            editIndex = -1;
            addBtn.setText("Add");
        };

        JPanel form = new JPanel(new GridLayout(0, 1));
        form.add(row(new JLabel("Time (HH:mm):"), timeInput, new JLabel("Title:"), titleInput));
        form.add(row(new JLabel("Details:"), detailsInput));
        form.add(dayRow);
        form.add(row(addBtn, clearBtn));
        frame.add(form, BorderLayout.NORTH);

        frame.add(row(editBtn, deleteBtn, testBtn), BorderLayout.SOUTH);

        addBtn.addActionListener(e -> {
            try {
                String time = LocalTime.parse(timeInput.getText().trim(), FMT).format(FMT);
                StringBuilder days = new StringBuilder();
                for (int i = 0; i < 7; i++) if (dayBoxes[i].isSelected()) days.append(i + 1);
                if (days.length() == 0) {
                    JOptionPane.showMessageDialog(frame, "Select at least one day.");
                    return;
                }
                String title = clean(titleInput.getText());
                if (title.isEmpty()) title = "Reminder";
                String line = time + "|" + days + "|" + title + "|" + clean(detailsInput.getText());

                List<String> entries = currentEntries();
                if (editIndex >= 0 && editIndex < entries.size()) entries.remove(editIndex);
                if (!entries.contains(line)) entries.add(line);
                Collections.sort(entries);
                model.clear();
                for (String s : entries) model.addElement(s);
                saveAlarms();
                clearForm.run();
            } catch (DateTimeParseException ex) {
                JOptionPane.showMessageDialog(frame, "Time must be 24-hour with 2 digits, e.g. 07:00 or 18:30");
            }
        });

        clearBtn.addActionListener(e -> clearForm.run());

        editBtn.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i < 0) return;
            String[] p = parse(model.get(i));
            if (p == null) return;
            timeInput.setText(p[0]);
            for (int d = 0; d < 7; d++) dayBoxes[d].setSelected(p[1].indexOf(String.valueOf(d + 1)) >= 0);
            titleInput.setText(p[2]);
            detailsInput.setText(p[3]);
            editIndex = i;
            addBtn.setText("Save Changes");
        });

        deleteBtn.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0) {
                model.remove(i);
                saveAlarms();
                clearForm.run();
            }
        });

        testBtn.addActionListener(e -> ring("Test reminder", "TEST"));

        frame.setVisible(true);
    }

    static JPanel row(java.awt.Component... items) {
        JPanel p = new JPanel(new FlowLayout());
        for (java.awt.Component c : items) p.add(c);
        return p;
    }

    // Runs every second: each entry fires once per day, on its selected days, at its time.
    static void checkAlarms() {
        if (ringing) return;
        LocalDateTime now = LocalDateTime.now();
        String hm = now.format(FMT);
        String dow = String.valueOf(now.getDayOfWeek().getValue());
        LocalDate today = now.toLocalDate();
        for (int i = 0; i < model.size(); i++) {
            String line = model.get(i);
            String[] p = parse(line);
            if (p == null) continue;
            if (p[0].equals(hm) && p[1].contains(dow) && !today.equals(lastFired.get(line))) {
                lastFired.put(line, today);
                String title = p[3].isEmpty() ? p[2] : p[2] + " (" + p[3] + ")";
                ring(title, hm);
                return;
            }
        }
    }

    static void ring(String title, String when) {
        if (ringing) return;
        ringing = true;
        stopRequested = false;

        Thread soundThread = new Thread(() -> {
            long end = System.currentTimeMillis() + RING_MS;
            while (!stopRequested && System.currentTimeMillis() < end) {
                playOnce();
                try {
                    Thread.sleep(GAP_MS);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            ringing = false;
        });
        soundThread.setDaemon(true);
        soundThread.start();

        SwingUtilities.invokeLater(() -> showDialog(title, when));
    }

    static void playOnce() {
        try {
            player = new ProcessBuilder("afplay", SOUND).start();
            player.waitFor();
        } catch (Exception ignored) {
            // afplay missing or interrupted; loop will try again until time runs out
        }
    }

    static void showDialog(String title, String when) {
        if (alarmDialog != null) alarmDialog.dispose();

        JDialog d = new JDialog((Frame) null, "Reminder", false);
        alarmDialog = d;
        d.setAlwaysOnTop(true);
        d.setLayout(new BorderLayout(10, 10));

        String text = when.equals("TEST") ? "Test reminder" : when + " - " + title;
        JLabel msg = new JLabel(text, JLabel.CENTER);
        msg.setFont(new Font("Arial", Font.BOLD, 20));
        d.add(msg, BorderLayout.CENTER);

        JButton stop = new JButton("Stop Alarm");
        stop.setFont(new Font("Arial", Font.BOLD, 18));
        stop.addActionListener(e -> stopAlarm());
        d.add(stop, BorderLayout.SOUTH);

        d.setSize(480, 180);
        d.setLocationRelativeTo(null);
        d.setVisible(true);
        d.toFront();

        // Auto-close after the 3-minute limit
        javax.swing.Timer auto = new javax.swing.Timer((int) RING_MS, e -> stopAlarm());
        auto.setRepeats(false);
        auto.start();
    }

    static void stopAlarm() {
        stopRequested = true;
        Process p = player;
        if (p != null) p.destroy();
        if (alarmDialog != null) {
            alarmDialog.dispose();
            alarmDialog = null;
        }
    }

    // Splits a stored line into [time, days, title, details]. Accepts the older 2-part format too.
    static String[] parse(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length == 2) return new String[] {p[0], ALL_DAYS, p[1], ""};
        if (p.length == 4) return p;
        return null;
    }

    static String describe(String line) {
        String[] p = parse(line);
        if (p == null) return line;
        String days = p[1].equals(ALL_DAYS) ? "Every day" : daysLabel(p[1]);
        String base = p[0] + "   " + days + "   " + p[2];
        return p[3].isEmpty() ? base : base + "  -  " + p[3];
    }

    static String daysLabel(String days) {
        StringBuilder sb = new StringBuilder();
        for (int d = 0; d < 7; d++) {
            if (days.indexOf(String.valueOf(d + 1)) >= 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(DAY_NAMES[d]);
            }
        }
        return sb.toString();
    }

    static String clean(String s) {
        return s.trim().replace("|", "/");
    }

    static List<String> currentEntries() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) entries.add(model.get(i));
        return entries;
    }

    static void loadAlarms() {
        if (!ALARM_FILE.exists()) return;
        try {
            List<String> lines = Files.readAllLines(ALARM_FILE.toPath(), StandardCharsets.UTF_8);
            List<String> entries = new ArrayList<>();
            for (String line : lines) {
                String[] p = parse(line.trim());
                if (p != null) entries.add(String.join("|", p));
            }
            Collections.sort(entries);
            for (String s : entries) model.addElement(s);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void saveAlarms() {
        try {
            Files.write(ALARM_FILE.toPath(), currentEntries(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
