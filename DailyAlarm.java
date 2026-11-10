import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerDateModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reminder app for macOS. A reminder has a date (or none, for repeating ones), a time,
 * the days it repeats on, a title and optional details. At the time, the sound repeats
 * and shows the reminder until you press "Stop" or 3 minutes pass.
 *
 * Saved in alarms.txt, one line per reminder:  date|HH:mm|days|title|details
 *   date  = yyyy-MM-dd for a one-time reminder, empty for a repeating one
 *   days  = digits 1 (Mon) .. 7 (Sun)
 */
public class DailyAlarm {
    static final File ALARM_FILE = new File("alarms.txt");
    static final String SOUND = "/System/Library/Sounds/Basso.aiff";
    static final String VOLUME = "2";               // afplay volume: 1 = normal, 2 = twice as loud
    static final long RING_MS = 3 * 60 * 1000L;   // max ring time: 3 minutes
    static final long GAP_MS = 1000L;              // pause between sound repeats
    static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);
    static final String ALL_DAYS = "1234567";
    static final String[] DAY_NAMES = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    static final String[] REPEAT_OPTIONS = {"Once, on a date", "Every day", "Selected weekdays"};
    static final ZoneId ZONE = ZoneId.systemDefault();

    static final DefaultListModel<String> model = new DefaultListModel<>();
    static final Map<String, LocalDate> lastFired = new HashMap<>();

    static volatile boolean ringing = false;
    static volatile boolean stopRequested = false;
    static volatile Process player;
    static JDialog alarmDialog;
    static int editIndex = -1;

    public static void main(String[] args) {
        loadAlarms();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // keep default look
            }
            buildUI();
        });
        new javax.swing.Timer(1000, e -> checkAlarms()).start();
    }

    static void buildUI() {
        JFrame frame = new JFrame("Reminders");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Header
        JLabel heading = new JLabel("Reminders");
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD, 22f));
        JLabel subheading = new JLabel("Pick a date and time. The alarm rings and shows your reminder.");
        subheading.setForeground(java.awt.Color.GRAY);
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(heading, BorderLayout.NORTH);
        header.add(subheading, BorderLayout.SOUTH);
        root.add(header, BorderLayout.NORTH);

        // New reminder form
        JTextField titleField = new JTextField(24);
        JTextField detailsField = new JTextField(24);
        JComboBox<String> repeatBox = new JComboBox<>(REPEAT_OPTIONS);

        JSpinner dateSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, Calendar.DAY_OF_MONTH));
        dateSpinner.setEditor(new JSpinner.DateEditor(dateSpinner, "yyyy-MM-dd"));

        JSpinner timeSpinner = new JSpinner(new SpinnerDateModel(new Date(), null, null, Calendar.MINUTE));
        timeSpinner.setEditor(new JSpinner.DateEditor(timeSpinner, "HH:mm"));

        JCheckBox[] dayBoxes = new JCheckBox[7];
        JPanel dayRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (int i = 0; i < 7; i++) {
            dayBoxes[i] = new JCheckBox(DAY_NAMES[i], true);
            dayRow.add(dayBoxes[i]);
        }

        JButton addBtn = new JButton("Add reminder");
        JButton clearBtn = new JButton("Clear");

        Runnable updateEnabled = () -> {
            int rep = repeatBox.getSelectedIndex();
            dateSpinner.setEnabled(rep == 0);
            for (JCheckBox b : dayBoxes) b.setEnabled(rep == 2);
        };
        repeatBox.addActionListener(e -> updateEnabled.run());

        Runnable clearForm = () -> {
            titleField.setText("");
            detailsField.setText("");
            repeatBox.setSelectedIndex(0);
            dateSpinner.setValue(new Date());
            timeSpinner.setValue(new Date());
            for (JCheckBox b : dayBoxes) b.setSelected(true);
            editIndex = -1;
            addBtn.setText("Add reminder");
            updateEnabled.run();
        };
        updateEnabled.run();

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("New reminder"));
        addRow(form, 0, "Title", titleField);
        addRow(form, 1, "Details", detailsField);
        addRow(form, 2, "Repeat", repeatBox);
        addRow(form, 3, "Date", dateSpinner);
        addRow(form, 4, "Time (24h)", timeSpinner);
        addRow(form, 5, "Weekdays", dayRow);

        JPanel formButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        formButtons.add(clearBtn);
        formButtons.add(addBtn);
        GridBagConstraints fb = new GridBagConstraints();
        fb.gridx = 0; fb.gridy = 6; fb.gridwidth = 2; fb.fill = GridBagConstraints.HORIZONTAL;
        form.add(formButtons, fb);

        // Saved reminders list
        JList<String> list = new JList<>(model);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                setText(describe((String) value));
                setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(560, 200));
        JPanel listCard = new JPanel(new BorderLayout());
        listCard.setBorder(BorderFactory.createTitledBorder("Saved reminders"));
        listCard.add(scroll, BorderLayout.CENTER);

        JButton editBtn = new JButton("Edit");
        JButton deleteBtn = new JButton("Delete");
        JButton testBtn = new JButton("Test sound");
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        listButtons.add(testBtn);
        listButtons.add(editBtn);
        listButtons.add(deleteBtn);
        listCard.add(listButtons, BorderLayout.SOUTH);

        JPanel center = new JPanel(new BorderLayout(0, 16));
        center.add(form, BorderLayout.NORTH);
        center.add(listCard, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        // Actions
        addBtn.addActionListener(e -> {
            String title = clean(titleField.getText());
            if (title.isEmpty()) title = "Reminder";
            String details = clean(detailsField.getText());
            LocalTime time = toLocalTime(timeSpinner.getValue()).withSecond(0).withNano(0);
            int rep = repeatBox.getSelectedIndex();

            String date = "";
            String days = ALL_DAYS;
            if (rep == 0) {
                LocalDate d = toLocalDate(dateSpinner.getValue());
                if (LocalDateTime.of(d, time).isBefore(LocalDateTime.now().withSecond(0).withNano(0))) {
                    JOptionPane.showMessageDialog(frame, "That date and time has already passed.");
                    return;
                }
                date = d.toString();
            } else if (rep == 2) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 7; i++) if (dayBoxes[i].isSelected()) sb.append(i + 1);
                if (sb.length() == 0) {
                    JOptionPane.showMessageDialog(frame, "Select at least one weekday.");
                    return;
                }
                days = sb.toString();
            }

            String line = String.join("|", date, time.format(TIME_FMT), days, title, details);
            List<String> entries = currentEntries();
            if (editIndex >= 0 && editIndex < entries.size()) entries.remove(editIndex);
            if (!entries.contains(line)) entries.add(line);
            setEntries(entries);
            saveAlarms();
            clearForm.run();
        });

        clearBtn.addActionListener(e -> clearForm.run());

        editBtn.addActionListener(e -> {
            int i = list.getSelectedIndex();
            if (i < 0) return;
            String[] p = parse(model.get(i));
            if (p == null) return;
            titleField.setText(p[3]);
            detailsField.setText(p[4]);
            if (!p[0].isEmpty()) {
                repeatBox.setSelectedIndex(0);
                dateSpinner.setValue(toDate(LocalDate.parse(p[0]).atStartOfDay()));
            } else if (p[2].equals(ALL_DAYS)) {
                repeatBox.setSelectedIndex(1);
            } else {
                repeatBox.setSelectedIndex(2);
            }
            timeSpinner.setValue(toDate(LocalDate.now().atTime(LocalTime.parse(p[1], TIME_FMT))));
            for (int d = 0; d < 7; d++) dayBoxes[d].setSelected(p[2].indexOf(String.valueOf(d + 1)) >= 0);
            updateEnabled.run();
            editIndex = i;
            addBtn.setText("Save changes");
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

        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(600, 620));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    static void addRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = row;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 8, 4, 8);
        panel.add(new JLabel(label), g);
        g.gridx = 1;
        g.weightx = 1;
        g.fill = GridBagConstraints.HORIZONTAL;
        panel.add(field, g);
    }

    // Runs every second: fires reminders at their time.
    static void checkAlarms() {
        if (ringing) return;
        LocalDateTime now = LocalDateTime.now();
        String hm = now.format(TIME_FMT);
        String dow = String.valueOf(now.getDayOfWeek().getValue());
        String today = now.toLocalDate().toString();
        for (int i = 0; i < model.size(); i++) {
            String line = model.get(i);
            String[] p = parse(line);
            if (p == null || !p[1].equals(hm)) continue;
            String title = p[4].isEmpty() ? p[3] : p[3] + " (" + p[4] + ")";
            if (!p[0].isEmpty()) {
                if (p[0].equals(today)) {
                    model.remove(i);          // one-time reminder is used up
                    saveAlarms();
                    ring(title, hm);
                    return;
                }
            } else if (p[2].contains(dow) && !now.toLocalDate().equals(lastFired.get(line))) {
                lastFired.put(line, now.toLocalDate());
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
            player = new ProcessBuilder("afplay", "-v", VOLUME, SOUND).start();
            player.waitFor();
        } catch (Exception ignored) {
            // afplay missing or interrupted; loop tries again until time runs out
        }
    }

    static void showDialog(String title, String when) {
        if (alarmDialog != null) alarmDialog.dispose();

        JDialog d = new JDialog((Frame) null, "Reminder", false);
        alarmDialog = d;
        d.setAlwaysOnTop(true);
        JPanel content = new JPanel(new BorderLayout(10, 14));
        content.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        String text = when.equals("TEST") ? "Test reminder" : when + "  " + title;
        JLabel msg = new JLabel(text, JLabel.CENTER);
        msg.setFont(msg.getFont().deriveFont(java.awt.Font.BOLD, 18f));
        content.add(msg, BorderLayout.CENTER);

        JButton stop = new JButton("Stop");
        stop.addActionListener(e -> stopAlarm());
        content.add(stop, BorderLayout.SOUTH);

        d.setContentPane(content);
        d.pack();
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

    // Splits a stored line into [date, time, days, title, details].
    // Also accepts the two older formats (time|title and time|days|title|details).
    static String[] parse(String line) {
        String[] p = line.split("\\|", -1);
        if (p.length == 2) return new String[] {"", p[0], ALL_DAYS, p[1], ""};
        if (p.length == 4) return new String[] {"", p[0], p[1], p[2], p[3]};
        if (p.length == 5) return p;
        return null;
    }

    static String describe(String line) {
        String[] p = parse(line);
        if (p == null) return line;
        String when;
        if (!p[0].isEmpty()) {
            when = LocalDate.parse(p[0]).format(DATE_FMT);
        } else if (p[2].equals(ALL_DAYS)) {
            when = "Every day";
        } else {
            when = daysLabel(p[2]);
        }
        String text = p[1] + "    " + when + "    " + p[3];
        return p[4].isEmpty() ? text : text + "  -  " + p[4];
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

    static Comparator<String> ORDER = Comparator.comparing((String s) -> {
        String[] p = parse(s);
        if (p == null) return "";
        return (p[0].isEmpty() ? "9999-99-99" : p[0]) + " " + p[1];
    });

    static String clean(String s) {
        return s.trim().replace("|", "/");
    }

    static LocalTime toLocalTime(Object value) {
        return ((Date) value).toInstant().atZone(ZONE).toLocalTime();
    }

    static LocalDate toLocalDate(Object value) {
        return ((Date) value).toInstant().atZone(ZONE).toLocalDate();
    }

    static Date toDate(LocalDateTime dt) {
        return Date.from(dt.atZone(ZONE).toInstant());
    }

    static List<String> currentEntries() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) entries.add(model.get(i));
        return entries;
    }

    static void setEntries(List<String> entries) {
        Collections.sort(entries, ORDER);
        model.clear();
        for (String s : entries) model.addElement(s);
    }

    static void loadAlarms() {
        if (!ALARM_FILE.exists()) return;
        try {
            String today = LocalDate.now().toString();
            List<String> entries = new ArrayList<>();
            for (String line : Files.readAllLines(ALARM_FILE.toPath(), StandardCharsets.UTF_8)) {
                String[] p = parse(line.trim());
                if (p == null) continue;
                if (!p[0].isEmpty() && p[0].compareTo(today) < 0) continue;   // past one-time reminder
                entries.add(String.join("|", p));
            }
            setEntries(entries);
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
