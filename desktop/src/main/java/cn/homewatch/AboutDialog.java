package cn.homewatch;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

/** Public author information only; this dialog never reads device or connection settings. */
final class AboutDialog extends JDialog {
    AboutDialog(Window owner) {
        super(owner, "关于" + AppIdentity.NAME, Dialog.ModalityType.MODELESS);
        setName("app.about.dialog");
        setIconImages(AppIdentity.icons());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        JPanel body = new JPanel(new BorderLayout(0, 18));
        body.setBackground(Color.WHITE);
        body.setBorder(BorderFactory.createEmptyBorder(22, 24, 18, 24));

        JPanel heading = new JPanel(new BorderLayout(14, 0));
        heading.setOpaque(false);
        heading.add(new JLabel(new ImageIcon(AppIdentity.drawIcon(56))), BorderLayout.WEST);
        JPanel title = new JPanel(new java.awt.GridLayout(2, 1, 0, 4));
        title.setOpaque(false);
        JLabel name = label(AppIdentity.NAME, 22, Font.BOLD);
        name.setName("about.name");
        title.add(name);
        JLabel version = label("版本 " + AppIdentity.VERSION, 12, Font.PLAIN);
        version.setForeground(new Color(126, 132, 139));
        version.setName("about.version");
        title.add(version);
        heading.add(title, BorderLayout.CENTER);
        body.add(heading, BorderLayout.NORTH);

        JPanel contacts = new JPanel(new GridBagLayout());
        contacts.setOpaque(false);
        addContact(contacts, 0, "作者", AppIdentity.AUTHOR, "about.author");
        addContact(contacts, 1, "QQ", AppIdentity.QQ, "about.qq");
        addContact(contacts, 2, "邮箱", AppIdentity.EMAIL, "about.email");
        body.add(contacts, BorderLayout.CENTER);

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        JButton close = NvrUi.button("关闭", null, true, this::dispose);
        close.setName("about.close");
        actions.add(close);
        body.add(actions, BorderLayout.SOUTH);
        setContentPane(body);
        getRootPane().setDefaultButton(close);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "about.close");
        getRootPane().getActionMap().put("about.close", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { dispose(); }
        });
        pack();
        setSize(Math.max(420, getWidth()), getHeight());
        setLocationRelativeTo(owner);
    }

    private static JLabel label(String text, int size, int style) {
        JLabel result = new JLabel(text);
        result.setFont(new Font("Microsoft YaHei UI", style, size));
        result.setForeground(new Color(64, 67, 72));
        return result;
    }

    private static void addContact(JPanel panel, int row, String caption, String text, String name) {
        GridBagConstraints key = new GridBagConstraints();
        key.gridx = 0; key.gridy = row;
        key.anchor = GridBagConstraints.WEST;
        key.insets = new Insets(7, 0, 7, 18);
        panel.add(label(caption, 13, Font.PLAIN), key);
        JTextField value = new JTextField(text);
        value.setName(name);
        value.setEditable(false);
        value.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        value.setBackground(Color.WHITE);
        value.setForeground(new Color(49, 51, 55));
        value.setFont(new Font("Microsoft YaHei UI", row == 0 ? Font.BOLD : Font.PLAIN, 14));
        value.getAccessibleContext().setAccessibleName(caption);
        value.setToolTipText("选中文字后可复制");
        GridBagConstraints content = new GridBagConstraints();
        content.gridx = 1; content.gridy = row;
        content.weightx = 1;
        content.fill = GridBagConstraints.HORIZONTAL;
        content.insets = new Insets(7, 0, 7, 0);
        panel.add(value, content);
    }
}
