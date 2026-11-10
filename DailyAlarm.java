import javax.swing.DefaultListModel;
import javax.swing.JButton;
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
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Frame;
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
 * Daily alarm for macOS. Keep a list of times (HH:mm, 24-hour). Every day, at each
 * time, the alarm sound repeats until you press "Stop Alarm" or 3 minutes pass.
 * Times are saved in alarms.txt next to this program.
 */
public class DailyAlarm {
    static final File ALARM_FILE = new File("alarms.txt");
    static final String SOUND = "/System/Library/Sounds/Glass.aiff";
    static final long RING_MS = 3 * 60 * 1000L;          // max ring time: 3 minutes
    static final long GAP_MS = 2000L;                     // pause between sound repeats
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");

    static final DefaultListModel<String> model = new DefaultListModel<>();
    static final Map<String, LocalDate> lastFired = new HashMap<>();

    static volatile boolean ringing = false;
    static volatile boolean stopRequested = false;
    static volatile Process player;
    static JDialog alarmDialog;

    public static void main(String[] args) {
        loadAlarms();
        SwingUtilities.invokeLater(DailyAlarm::buildUI);
        new javax.swing.Timer(1000, e -> checkAlarms()).start();
    }

    static void buildUI() {
        JFrame frame = new JFrame("Daily Alarm");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(380, 460);
        frame.setLayout(new BorderLayout(8, 8));

        Font big = new Font("Arial", Font.PLAIN, 22);

        JList<String> list = new JList<>(model);
        list.setFont(big);
        frame.add(new JScrollPane(list), BorderLayout.CENTER);

        JTextField input = new JTextField("07:00", 6);
        input.setFont(big);
        JButton addBtn = new JButton("Add");
        JButton testBtn = new JButton("Test Alarm");
        JButton deleteBtn = new JButton("Delete Selected");

        JPanel top = new JPanel(new FlowLayout());
        top.add(new JLabel("Time (HH:mm):"));
        top.add(input);
        top.add(addBtn);
        frame.add(top, BorderLayout.NORTH);

        JPanel bottom = new JPanel(new FlowLayout());
        bottom.add(testBtn);
        bottom.add(deleteBtn);
        frame.add(bottom, BorderLayout.SOUTH);

        addBtn.addActionListener(e -> {
            String text = input.getText().trim();
            try {
                String time = LocalTime.parse(text, FMT).format(FMT);
                if (!model.contains(time)) {
                    List<String> times = currentTimes();
                    times.add(time);
                    Collections.sort(times);
                    model.clear();
                    for (String t : times) model.addElement(t);
                    saveAlarms();
                }
                input.setText("");
            } catch (DateTimeParseException ex) {
                JOptionPane.showMessageDialog(frame, "Use 24-hour time with 2 digits, e.g. 07:00 or 18:30");
            }
        });

        deleteBtn.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i >= 0) {
                model.remove(i);
                saveAlarms();
            }
        });

        testBtn.addActionListener(e -> ring("TEST"));

        frame.setVisible(true);
    }

    // Runs every second: fires once per matching time per day.
    static void checkAlarms() {
        LocalDateTime now = LocalDateTime.now();
        String hm = now.format(FMT);
        LocalDate today = now.toLocalDate();
        if (!ringing && model.contains(hm) && !today.equals(lastFired.get(hm))) {
            lastFired.put(hm, today);
            ring(hm);
        }
    }

    static void ring(String label) {
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

        SwingUtilities.invokeLater(() -> showDialog(label));
    }

    static void playOnce() {
        try {
            player = new ProcessBuilder("afplay", SOUND).start();
            player.waitFor();
        } catch (Exception ignored) {
            // afplay missing or interrupted; loop will try again until time runs out
        }
    }

    static void showDialog(String label) {
        if (alarmDialog != null) alarmDialog.dispose();

        JDialog d = new JDialog((Frame) null, "Alarm", false);
        alarmDialog = d;
        d.setAlwaysOnTop(true);
        d.setLayout(new BorderLayout(10, 10));

        JLabel msg = new JLabel(
                label.equals("TEST") ? "Test alarm!" : "It's " + label + " - time for your schedule!",
                JLabel.CENTER);
        msg.setFont(new Font("Arial", Font.BOLD, 20));
        d.add(msg, BorderLayout.CENTER);

        JButton stop = new JButton("Stop Alarm");
        stop.setFont(new Font("Arial", Font.BOLD, 18));
        stop.addActionListener(e -> stopAlarm());
        d.add(stop, BorderLayout.SOUTH);

        d.setSize(420, 180);
        d.setLocationRelativeTo(null);
        d.setVisible(true);
        d.toFront();

        // Auto-close the dialog when the 3-minute limit is reached
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

    static List<String> currentTimes() {
        List<String> times = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) times.add(model.get(i));
        return times;
    }

    static void loadAlarms() {
        if (!ALARM_FILE.exists()) return;
        try {
            List<String> lines = Files.readAllLines(ALARM_FILE.toPath(), StandardCharsets.UTF_8);
            List<String> times = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (!t.isEmpty()) times.add(t);
            }
            Collections.sort(times);
            for (String t : times) model.addElement(t);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void saveAlarms() {
        try {
            Files.write(ALARM_FILE.toPath(), currentTimes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
