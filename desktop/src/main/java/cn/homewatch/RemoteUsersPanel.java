package cn.homewatch;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.*;

/** Desktop-only account administration; remote clients cannot grant their own channels. */
final class RemoteUsersPanel extends JPanel {
    private final RemoteAccessService service;
    private final BiConsumer<String,Boolean> notice;
    private final DefaultListModel<RemoteAccessService.UserInfo> model=new DefaultListModel<>();
    private final JList<RemoteAccessService.UserInfo> list=new JList<>(model);
    private final JButton add=NvrUi.button("新建",null,false,null),edit=NvrUi.button("编辑",null,false,null),remove=NvrUi.button("删除",null,false,null);
    private final JLabel summary=new JLabel("还没有用户");
    private List<RemoteAccessService.UserInfo> current=List.of();
    private boolean busy;
    RemoteUsersPanel(RemoteAccessService service,BiConsumer<String,Boolean> notice){
        this.service=service;this.notice=notice;setOpaque(false);setLayout(new BorderLayout(0,5));setBorder(BorderFactory.createEmptyBorder(6,7,6,7));
        summary.setFont(NvrUi.FONT);summary.setForeground(new Color(100,106,114));add(summary,BorderLayout.NORTH);
        list.setName("remote.users.list");list.setFont(NvrUi.FONT);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setFixedCellHeight(30);
        list.setCellRenderer(new DefaultListCellRenderer(){@Override public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean selected,boolean focus){
            JLabel c=(JLabel)super.getListCellRendererComponent(l,value,index,selected,focus);if(value instanceof RemoteAccessService.UserInfo u){c.setText(" "+u.username()+"  ·  "+(u.enabled()?"启用":"停用")+"  ·  "+visibleCount(u)+" 路");c.setToolTipText(u.username());}c.setBorder(BorderFactory.createEmptyBorder(0,6,0,6));return c;}});
        JScrollPane scroll=new JScrollPane(list);scroll.setPreferredSize(new Dimension(230,99));scroll.setBorder(BorderFactory.createLineBorder(new Color(218,221,225)));add(scroll,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(0,5));bottom.setOpaque(false);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));actions.setOpaque(false);actions.add(add);actions.add(Box.createHorizontalStrut(5));actions.add(edit);actions.add(Box.createHorizontalStrut(5));actions.add(remove);bottom.add(actions,BorderLayout.NORTH);
        JLabel hint=new JLabel("<html>App 使用用户名、密码登录<br>可查看范围在这里勾选<br>新设备须另行授权</html>");hint.setFont(NvrUi.FONT);hint.setForeground(new Color(119,126,135));bottom.add(hint,BorderLayout.SOUTH);add(bottom,BorderLayout.SOUTH);
        add.setName("remote.users.add");edit.setName("remote.users.edit");remove.setName("remote.users.remove");
        add.addActionListener(e->openEditor(null));edit.addActionListener(e->openEditor(list.getSelectedValue()));remove.addActionListener(e->deleteSelected());list.addListSelectionListener(e->updateButtons());
        list.addMouseListener(new java.awt.event.MouseAdapter(){@Override public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2&&!busy&&list.getSelectedValue()!=null)openEditor(list.getSelectedValue());}});
        refresh();
    }
    private int visibleCount(RemoteAccessService.UserInfo user){return (int)service.availableCameras().stream().filter(c->user.channelIds().contains(c.id())).count();}
    void refresh(){
        List<RemoteAccessService.UserInfo> users=service.users();
        if(!users.equals(current)){String selected=list.getSelectedValue()==null?null:list.getSelectedValue().id();current=users;model.clear();for(var u:users)model.addElement(u);if(selected!=null)for(int i=0;i<model.size();i++)if(model.get(i).id().equals(selected))list.setSelectedIndex(i);if(list.isSelectionEmpty()&&!model.isEmpty())list.setSelectedIndex(0);}
        summary.setText(users.isEmpty()?"还没有用户，点击新建":users.size()+" / 64 个用户 · 权限按摄像头分配");list.repaint();updateButtons();
    }
    private void updateButtons(){add.setEnabled(!busy&&model.size()<64);edit.setEnabled(!busy&&list.getSelectedValue()!=null);remove.setEnabled(!busy&&list.getSelectedValue()!=null);}
    private void openEditor(RemoteAccessService.UserInfo existing){
        if(busy)return;
        Window owner=SwingUtilities.getWindowAncestor(this);JDialog dialog=new JDialog(owner,existing==null?"新建 App 用户":"编辑 App 用户",Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setName("remote.users.dialog");dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);dialog.setMinimumSize(new Dimension(450,440));dialog.setSize(490,500);dialog.setLocationRelativeTo(owner);
        JPanel content=new JPanel(new BorderLayout(0,10));content.setBackground(new Color(248,249,251));content.setBorder(BorderFactory.createEmptyBorder(16,18,14,18));dialog.setContentPane(content);
        JTextField username=new JTextField(existing==null?"":existing.username());JPasswordField password=new JPasswordField();NvrUi.styleField(username);NvrUi.styleField(password);username.setName("remote.users.username");password.setName("remote.users.password");password.setEchoChar('●');
        JCheckBox enabled=new JCheckBox("启用该用户",existing==null||existing.enabled()),show=new JCheckBox("显示新密码");enabled.setName("remote.users.enabled");for(JCheckBox box:List.of(enabled,show)){box.setOpaque(false);box.setFont(NvrUi.FONT);}show.addActionListener(e->password.setEchoChar(show.isSelected()?(char)0:'●'));
        JPanel form=new JPanel(new GridBagLayout());form.setOpaque(false);formRow(form,0,"用户名",username);formRow(form,1,existing==null?"密码":"新密码",password);
        JPanel top=new JPanel();top.setOpaque(false);top.setLayout(new BoxLayout(top,BoxLayout.Y_AXIS));top.add(form);JPanel toggles=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));toggles.setOpaque(false);toggles.add(enabled);toggles.add(Box.createHorizontalStrut(16));toggles.add(show);top.add(toggles);
        JLabel passwordHint=new JLabel(existing==null?"密码至少 8 位；用户名须与 Web 管理员不同":"新密码至少 8 位；留空保持原密码");passwordHint.setFont(NvrUi.FONT);passwordHint.setForeground(new Color(119,126,135));passwordHint.setBorder(BorderFactory.createEmptyBorder(5,0,0,0));top.add(passwordHint);content.add(top,BorderLayout.NORTH);
        List<RemoteAccessService.CameraInfo> cameras=service.availableCameras();Map<String,JCheckBox> channels=new LinkedHashMap<>();JPanel checks=new JPanel();checks.setBackground(Color.WHITE);checks.setLayout(new BoxLayout(checks,BoxLayout.Y_AXIS));checks.setBorder(BorderFactory.createEmptyBorder(5,8,5,8));
        for(var camera:cameras){JCheckBox check=new JCheckBox(String.format("CH%02d · %s",camera.number(),camera.name()),existing!=null&&existing.channelIds().contains(camera.id()));check.setName("remote.users.channel."+camera.id());check.setToolTipText(camera.name());check.setFont(NvrUi.FONT);check.setOpaque(false);check.setPreferredSize(new Dimension(300,29));check.setMaximumSize(new Dimension(Integer.MAX_VALUE,29));checks.add(check);channels.put(camera.id(),check);}
        JPanel permission=new JPanel(new BorderLayout(0,5));permission.setOpaque(false);JLabel caption=new JLabel("允许查看的摄像头（实时、回放及下载）");caption.setFont(NvrUi.FONT.deriveFont(Font.BOLD));permission.add(caption,BorderLayout.NORTH);JScrollPane channelScroll=new JScrollPane(checks);channelScroll.setName("remote.users.channels");channelScroll.setBorder(BorderFactory.createLineBorder(new Color(218,221,225)));permission.add(channelScroll,BorderLayout.CENTER);
        JPanel select=new JPanel(new FlowLayout(FlowLayout.LEFT,0,0));select.setOpaque(false);JButton all=NvrUi.button("全选",null,false,null),none=NvrUi.button("清空",null,false,null);all.setName("remote.users.selectAll");none.setName("remote.users.selectNone");all.addActionListener(e->channels.values().forEach(c->c.setSelected(true)));none.addActionListener(e->channels.values().forEach(c->c.setSelected(false)));select.add(all);select.add(Box.createHorizontalStrut(7));select.add(none);JLabel emptyHint=new JLabel("  未勾选时，该用户看不到画面");emptyHint.setFont(NvrUi.FONT);emptyHint.setForeground(new Color(119,126,135));select.add(emptyHint);permission.add(select,BorderLayout.SOUTH);content.add(permission,BorderLayout.CENTER);
        JLabel result=new JLabel(" ");result.setName("remote.users.result");result.setFont(NvrUi.FONT);JButton save=NvrUi.button("保存用户",null,true,null),cancel=NvrUi.button("取消",null,false,null);save.setName("remote.users.save");cancel.setName("remote.users.cancel");
        JPanel footer=new JPanel(new BorderLayout(0,5));footer.setOpaque(false);JLabel revokeHint=new JLabel("修改后该用户需重新登录，其他用户不受影响");revokeHint.setFont(NvrUi.FONT);revokeHint.setForeground(new Color(119,126,135));footer.add(revokeHint,BorderLayout.NORTH);footer.add(result,BorderLayout.CENTER);JPanel buttons=new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));buttons.setOpaque(false);buttons.add(cancel);buttons.add(save);footer.add(buttons,BorderLayout.SOUTH);content.add(footer,BorderLayout.SOUTH);
        cancel.addActionListener(e->dialog.dispose());save.addActionListener(e->{
            char[] secret=password.getPassword();String name=username.getText().strip();boolean nextEnabled=enabled.isSelected();Set<String> selected=new LinkedHashSet<>();channels.forEach((id,check)->{if(check.isSelected())selected.add(id);});
            save.setEnabled(false);cancel.setEnabled(false);username.setEnabled(false);password.setEnabled(false);enabled.setEnabled(false);all.setEnabled(false);none.setEnabled(false);show.setEnabled(false);channels.values().forEach(c->c.setEnabled(false));result.setForeground(new Color(119,126,135));result.setText("正在保存…");
            Thread.startVirtualThread(()->{String error=null;try{service.saveUser(existing==null?null:existing.id(),name,new String(secret),nextEnabled,selected);}catch(Exception failure){error=Objects.requireNonNullElse(failure.getMessage(),"保存失败，请重试");}finally{Arrays.fill(secret,(char)0);}String message=error;SwingUtilities.invokeLater(()->{
                if(message==null){refresh();notice.accept(existing==null?(service.state().apiRunning()?"用户已创建，可在 App 登录":"用户已创建；请开启 App API 后登录"):"用户已保存，该用户需重新登录",false);dialog.dispose();}
                else{result.setForeground(new Color(202,43,47));result.setText("<html>"+escape(message)+"</html>");save.setEnabled(true);cancel.setEnabled(true);username.setEnabled(true);password.setEnabled(true);enabled.setEnabled(true);all.setEnabled(true);none.setEnabled(true);show.setEnabled(true);channels.values().forEach(c->c.setEnabled(true));dialog.revalidate();}
            });});
        });
        dialog.getRootPane().setDefaultButton(save);dialog.addWindowListener(new java.awt.event.WindowAdapter(){@Override public void windowClosed(java.awt.event.WindowEvent e){password.setText("");}});dialog.setVisible(true);
    }
    private void deleteSelected(){
        var user=list.getSelectedValue();if(user==null||busy)return;
        int answer=JOptionPane.showConfirmDialog(this,"删除用户“"+user.username()+"”？\n该用户将立即退出远程连接。","删除用户",JOptionPane.OK_CANCEL_OPTION,JOptionPane.WARNING_MESSAGE);if(answer!=JOptionPane.OK_OPTION)return;
        busy=true;updateButtons();Thread.startVirtualThread(()->{String error=null;try{service.removeUser(user.id());}catch(Exception failure){error=Objects.requireNonNullElse(failure.getMessage(),"删除失败，请重试");}String message=error;SwingUtilities.invokeLater(()->{busy=false;refresh();notice.accept(message==null?"用户已删除":message,message!=null);});});
    }
    private static void formRow(JPanel form,int row,String label,Component field){GridBagConstraints c=new GridBagConstraints();c.gridx=0;c.gridy=row;c.insets=new Insets(0,0,7,12);c.anchor=GridBagConstraints.WEST;JLabel caption=new JLabel(label);caption.setFont(NvrUi.FONT);form.add(caption,c);c.gridx=1;c.weightx=1;c.fill=GridBagConstraints.HORIZONTAL;c.insets=new Insets(0,0,7,0);form.add(field,c);}
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
}
