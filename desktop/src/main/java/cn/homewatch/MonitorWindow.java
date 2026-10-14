package cn.homewatch;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Single NVR console. The live view and archive share one video surface. */
public final class MonitorWindow {
    public interface Listener {
        void onConnectToggle(); void onAudioToggle(); void onSnapshot();
        void onRecordingToggle(); default void onPlayback() {} default void onLive() {}
        default void onStartAllRecording() { onRecordingToggle(); }
        default void onStopAllRecording() { onRecordingToggle(); }
        void onSettings(); void onOpenFolder(); void onClose();
        default void onSelectDevice(String id) {}
        default void onAddDevice() {}
        default void onRemoveDevice(String id) {}
        default void onLayoutChanged(int tiles) {}
        default void onManageDevices() { onAddDevice(); }
    }
    public record DeviceSummary(String id, String name, String endpoint) {
        public DeviceSummary {
            id=Objects.requireNonNull(id);name=Objects.requireNonNullElse(name,"摄像头");
            endpoint=publicEndpoint(Objects.requireNonNullElse(endpoint,""));
        }
        @Override public String toString(){return name;}
    }
    private static final Color TITLE=new Color(49,51,55), SURFACE=new Color(248,248,249),
        BORDER=new Color(216,218,221), TEXT=new Color(64,67,72), MUTED=new Color(132,136,142),
        RED=new Color(207,44,49), GREEN=new Color(45,147,92), VIDEO=new Color(24,26,29);
    private static final String FONT_NAME="Microsoft YaHei UI";
    private final Listener listener;
    private final AtomicBoolean repaintQueued=new AtomicBoolean();
    private volatile BufferedImage latestFrame;
    private PlaybackWindow playback;
    private NvrSettingsPanel settings;
    private JFrame window;
    private AboutDialog aboutDialog;
    private JPanel root, header, titleBar, footer, content, placeholder, settingsDock, sideBody;
    private JLabel sideTitle, gridPage;
    private JComboBox<DeviceSummary> deviceSelector;
    private JComboBox<String> layoutSelector;
    private JButton previousGridPage,nextGridPage;
    private MultiCameraGrid grid;
    private JComponent deviceManager;
    private List<DeviceSummary> devices=List.of();
    private volatile String selectedDeviceId="";
    private boolean syncingDevices,syncingLayout;
    private int expandedGridLayout;
    private JLabel cameraTitle,statusLabel,connectionDetail,clockLabel,recordLabel;
    private StatusDot statusDot;
    private JButton connectButton,audioButton,snapshotButton,recordingButton,startAllRecordingButton,stopAllRecordingButton,fullscreenButton,liveButton,replayButton,settingsButton,storageSummary;
    private String state="DISCONNECTED",connectionText="点击实时预览连接摄像头",cameraName="摄像头 1";
    private boolean liveAudio,recording,recordingActive,fullscreen,replayMode;
    private boolean settingsShown=true, settingsBeforeFullscreen;
    private Rectangle normalBounds;
    private int normalState;
    private Timer uiTimer;
    private long noticeExpires;

    public MonitorWindow(Listener listener) {
        this.listener=Objects.requireNonNull(listener);
        onEdtAndWait(this::buildUi);
    }
    public void attachConsole(PlaybackWindow playback,NvrSettingsPanel settings) {
        onEdtAndWait(()->{
            boolean remoteExpanded=this.settings!=null && this.settings.isRemoteAccessExpanded();
            if(this.settings!=null && this.settings!=settings)this.settings.setStorageSummaryListener(null);
            if(this.playback!=null && this.playback!=playback){
                this.playback.pause();this.playback.setLiveSurface(null);content.remove(this.playback.component());
            }
            this.playback=Objects.requireNonNull(playback);
            this.settings=Objects.requireNonNull(settings);
            content.remove(placeholder);
            content.add(playback.component(),BorderLayout.CENTER);
            if(settingsDock==null){
            settingsDock=panel(new BorderLayout());
            settingsDock.setPreferredSize(new Dimension(280,1));
            settingsDock.setBorder(BorderFactory.createMatteBorder(0,1,0,0,BORDER));
            JPanel settingsHeader=panel(new BorderLayout(6,0));
            settingsHeader.setBorder(BorderFactory.createEmptyBorder(5,12,5,8));
            sideTitle=label("录像机设置",12,Font.BOLD,TEXT);
            settingsHeader.add(sideTitle,BorderLayout.CENTER);
            JButton collapse=button("收起","",false,0,()->setSettingsVisible(false));
            collapse.setIcon(null);collapse.setName("settings.collapse");
            collapse.setToolTipText("收起右侧面板（Esc），未保存的修改会保留");
            collapse.getAccessibleContext().setAccessibleName("收起右侧面板");
            settingsHeader.add(collapse,BorderLayout.EAST);
            settingsDock.add(settingsHeader,BorderLayout.NORTH);
            sideBody=panel(new BorderLayout());settingsDock.add(sideBody,BorderLayout.CENTER);
            JPanel deviceActions=panel(new GridLayout(0,2,6,6));
            deviceActions.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1,0,0,0,BORDER),BorderFactory.createEmptyBorder(7,8,7,8)));
            deviceActions.add(connectButton);
            deviceActions.add(recordingButton);
            deviceActions.add(button("录像目录","folder",false,0,listener::onOpenFolder));
            settingsDock.add(deviceActions,BorderLayout.SOUTH);
            content.add(settingsDock,BorderLayout.EAST);
            }
            // Changing the video channel must not dismiss the device form or its draft.
            if(deviceManager==null){sideBody.removeAll();sideBody.add(settings,BorderLayout.CENTER);}
            if(remoteExpanded)settings.expandRemoteAccess();
            sideTitle.setText(deviceManager==null?"录像机设置":"设备管理");
            setSettingsVisible(settingsShown);
            NvrSettingsPanel currentSettings=settings;
            settings.setStorageSummaryListener((policy,used,dirty)->{
                if(this.settings==currentSettings)setStorageSummary(policy,used,dirty);
            });
            playback.setLiveSurface(grid);
            playback.setChannelName(cameraName);
            playback.setLiveFrame(latestFrame);
            playback.setLiveStatus(state,connectionText);
            playback.setVideoFullscreen(fullscreen);
            // Each channel retains its own live/replay mode, query and archive position.
            setPlaybackMode(!playback.isLiveMode());
            content.revalidate(); content.repaint();
        });
    }
    public void showWindow() { onEdt(()->{
        uiTimer.start();
        if(window!=null){
            window.setExtendedState(window.getExtendedState() & ~JFrame.ICONIFIED);
            Rectangle b=window.getBounds(); boolean onScreen=false;
            for(var screen:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
                var visible=screen.getDefaultConfiguration().getBounds().intersection(new Rectangle(b.x,b.y,b.width,40));
                if(visible.width>=120 && visible.height>=24){onScreen=true;break;}
            }
            if(!onScreen)window.setLocationRelativeTo(null);
            window.setVisible(true);
            boolean top=window.isAlwaysOnTop();
            if(window.isAlwaysOnTopSupported())window.setAlwaysOnTop(true);
            window.toFront();window.requestFocus();
            if(!top && window.isAlwaysOnTopSupported())window.setAlwaysOnTop(false);
        }
    }); }
    public Component dialogParent(){return window==null?root:window;}
    public void setDevices(List<DeviceSummary> summaries,String selectedId){onEdt(()->{
        devices=List.copyOf(summaries);selectedDeviceId=Objects.requireNonNullElse(selectedId,"");
        syncingDevices=true;
        try{deviceSelector.removeAllItems();for(DeviceSummary summary:devices){deviceSelector.addItem(summary);
            if(summary.id().equals(selectedDeviceId))deviceSelector.setSelectedItem(summary);}}
        finally{syncingDevices=false;}
        grid.setDevices(devices,selectedDeviceId);
        latestFrame=grid.frame(selectedDeviceId);
        if(deviceManager instanceof DeviceManagerPanel manager)manager.setDevices(devices,selectedDeviceId);
        deviceSelector.setEnabled(!devices.isEmpty());refreshActions();
    });}
    public String selectedDeviceId(){return selectedDeviceId;}
    public void setDeviceFrame(String id,BufferedImage frame){
        // The controller dispatches the selected channel's setFrame on EDT after rechecking its id.
        grid.setFrame(id,frame);
    }
    public void setDeviceState(String id,String state,boolean recording){grid.setState(id,state,recording);}
    public void setGridLayout(int tiles){onEdt(()->{
        grid.setTiles(tiles);syncingLayout=true;
        try{layoutSelector.setSelectedIndex(tiles==1?0:tiles==4?1:2);}finally{syncingLayout=false;}
        refreshGridControls();
    });}
    public int gridLayout(){return grid.tileCount();}
    public MultiCameraGrid grid(){return grid;}
    public void showDeviceManager(JComponent manager){onEdt(()->{
        if(settingsDock==null)return;
        deviceManager=Objects.requireNonNull(manager);
        sideBody.removeAll();sideBody.add(manager,BorderLayout.CENTER);
        if(manager instanceof DeviceManagerPanel panel)panel.setDevices(devices,selectedDeviceId);
        sideTitle.setText("设备管理");setSettingsVisible(true);sideBody.revalidate();sideBody.repaint();
    });}
    public void setFrame(BufferedImage frame){
        latestFrame=frame;
        if(playback!=null)playback.setLiveFrame(frame);
        if(repaintQueued.compareAndSet(false,true))onEdt(()->{repaintQueued.set(false);refreshActions();});
    }
    public BufferedImage currentFrame(){return playback==null?latestFrame:playback.currentFrame();}
    public void setPlaybackMode(boolean replay){onEdt(()->{
        replayMode=replay;
        if(playback!=null)playback.setLiveMode(!replay);
        layoutSelector.setEnabled(!replay);
        refreshGridControls();
        liveButton.setForeground(replay?TEXT:RED); replayButton.setForeground(replay?RED:TEXT);
        liveButton.setSelected(!replay); replayButton.setSelected(replay);
        refreshActions();restoreDetail();
    });}
    public boolean isPlaybackMode(){return replayMode;}
    public void setConnectionState(String next,String detail){onEdt(()->{
        state=next==null?"DISCONNECTED":next;
        connectionText=redact(detail==null?"":detail);
        Color color=switch(state){case "CONNECTED"->GREEN;case "ERROR"->RED;default->MUTED;};
        statusDot.color=color;statusDot.repaint();statusLabel.setForeground(color);
        statusLabel.setText(switch(state){case "CONNECTED"->"预览在线";case "CONNECTING"->"预览连接中";case "RETRYING"->"预览重连中";case "ERROR"->"预览连接失败";default->"预览未连接";});
        boolean stopped=state.equals("DISCONNECTED")||state.equals("ERROR");
        connectButton.setText(stopped?"连接预览":"停止预览");
        connectButton.setIcon(new LineIcon(stopped?"play":"stop",16));
        connectButton.setToolTipText(stopped?"连接摄像头并查看实时画面":"只停止实时预览，独立录像继续运行");
        statusLabel.setToolTipText(connectionText);
        if(playback!=null)playback.setLiveStatus(state,connectionText);
        restoreDetail();
    });}
    public void setCameraInfo(String name,String endpoint,String transport){onEdt(()->{
        cameraName=name==null||name.isBlank()?"摄像头 1":name;
        cameraTitle.setText(cameraName);
        cameraTitle.setToolTipText(cameraName+" · "+publicEndpoint(endpoint)+" · "+transport);
        if(playback!=null)playback.setChannelName(cameraName);
        if(sideTitle!=null && deviceManager==null)sideTitle.setText("录像机设置");
        if(window!=null)window.setTitle(AppIdentity.NAME+" · "+cameraName);
    });}
    public void setAudioEnabled(boolean enabled){onEdt(()->{
        liveAudio=enabled;if(playback!=null)playback.setLiveAudio(enabled);refreshActions();
    });}
    public void setRecording(boolean active){setRecordingState(active,active);}
    public void setRecordingState(boolean requested,boolean active){setRecordingState(requested,active,"");}
    public void setRecordingState(boolean requested,boolean active,String detail){onEdt(()->{
        recording=requested;recordingActive=active;
        recordingButton.setText(requested?"停止本通道":"录制本通道");
        recordingButton.setForeground(requested?RED:TEXT);
        recordingButton.setToolTipText("控制当前所选摄像头的录像；切换实时和回放不会中断各通道录制");
    });}
    /** Recorder-wide status is independent of the selected preview or playback channel. */
    public void setRecordingGroupState(int configured,int requested,int active,int unconfigured){onEdt(()->{
        startAllRecordingButton.setEnabled(configured>requested);
        stopAllRecordingButton.setEnabled(requested>0);
        String text=active>0?"● 录像 "+active+" / "+configured+" 路":requested>0?"● 录像连接中":"● 未录像";
        int waiting=Math.max(0,requested-active);
        if(active>0 && waiting>0)text+=" · "+waiting+" 路连接中";
        recordLabel.setText(text);
        recordLabel.setForeground(active>0?new Color(255,108,111):requested>0?new Color(240,184,91):new Color(170,173,178));
        recordLabel.setToolTipText("全部摄像头：已设置 "+configured+" 路，已开启录像 "+requested+" 路，正在写入 "+active+" 路"
                +(unconfigured>0?"；另有 "+unconfigured+" 路尚未填写地址":"")+"。停止预览和切换回放不影响录像");
    });}
    public void showNotice(String message,boolean error){onEdt(()->{
        String safe=redact(message==null?"":message);
        connectionDetail.setText(safe);connectionDetail.setToolTipText(safe);
        connectionDetail.setForeground(error?RED:GREEN);
        noticeExpires=System.currentTimeMillis()+(error?12000:7000);
    });}
    public void showProgressNotice(String message){onEdt(()->{
        if(System.currentTimeMillis()<noticeExpires)return;
        String safe=redact(message==null?"":message);
        connectionDetail.setText(safe);connectionDetail.setToolTipText(safe);
        connectionDetail.setForeground(MUTED);noticeExpires=System.currentTimeMillis()+7000;
    });}
    private void setStorageSummary(RecorderSettings policy,long usedBytes,boolean dirty){
        double gib=1024.0*1024*1024;
        String limit=new java.text.DecimalFormat("0.##",java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ROOT)).format(policy.maxBytes()/gib);
        storageSummary.setText(String.format(java.util.Locale.ROOT,"总存储 %.1f / %s GB · %d天%s",
                usedBytes/gib,limit,policy.keepDays(),dirty?" · 未保存":""));
        storageSummary.setToolTipText("所有已添加摄像头合计，当前生效：最大占用 "+String.format(java.util.Locale.ROOT,"%.2f GB",policy.maxBytes()/gib)
                +"，保留 "+policy.keepDays()+" 天。点击修改保存目录、容量和天数"+(dirty?"；右侧修改尚未保存":""));
        storageSummary.setForeground(dirty?RED:TEXT);
        footer.revalidate();
    }
    private void restoreSettings(){
        if(sideBody!=null&&settings!=null){sideBody.removeAll();sideBody.add(settings,BorderLayout.CENTER);deviceManager=null;
            sideTitle.setText("录像机设置");sideBody.revalidate();sideBody.repaint();}
    }
    public void showSettings(){onEdt(()->{restoreSettings();setSettingsVisible(true);if(settings!=null)settings.expandStorage();});}
    public void showCameraSettings(){onEdt(()->{restoreSettings();setSettingsVisible(true);if(settings!=null)settings.expandCamera();});}
    public void showRemoteSettings(){onEdt(()->{restoreSettings();setSettingsVisible(true);if(settings!=null)settings.expandRemoteAccess();});}
    public void toggleSettingsPanel(){onEdt(()->{
        if(settingsDock==null)return;
        if(settingsShown && deviceManager==null)setSettingsVisible(false);
        else{restoreSettings();setSettingsVisible(true);}
    });}
    public boolean isSettingsVisible(){return settingsShown && settingsDock!=null && settingsDock.isVisible();}
    private void setSettingsVisible(boolean visible){
        settingsShown=visible;
        if(settingsDock!=null)settingsDock.setVisible(visible);
        settingsButton.setSelected(visible && deviceManager==null);
        settingsButton.setToolTipText(visible && deviceManager==null
                ?"收起录像机设置（Esc），未保存的修改会保留"
                :"打开录像机设置：录像存储、人员识别、远程用户和加密连接");
        content.revalidate();content.repaint();
    }
    public void disposeWindow(){onEdt(()->{uiTimer.stop();if(settings!=null)settings.setStorageSummaryListener(null);
        if(deviceManager instanceof DeviceManagerPanel manager)manager.close();if(window!=null)window.dispose();});}
    public BufferedImage renderPreview(int width,int height){
        if(width<760||height<540)throw new IllegalArgumentException("Preview too small");
        BufferedImage[] result=new BufferedImage[1];
        onEdtAndWait(()->{
            refreshActions();
            Dimension old=root.getSize();root.setSize(width,height);layoutRecursively(root);
            var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            var g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            root.printAll(g);g.dispose();result[0]=image;
            if(window!=null&&window.isShowing()){root.setSize(old);layoutRecursively(root);}
        });return result[0];
    }
    private void buildUi(){
        grid=new MultiCameraGrid(listener::onSelectDevice,this::toggleChannelExpansion,listener::onAddDevice);
        root=new JPanel(new BorderLayout());root.setBackground(SURFACE);
        header=buildHeader();root.add(header,BorderLayout.NORTH);
        content=new JPanel(new BorderLayout(1,0));content.setBackground(BORDER);
        placeholder=new JPanel();placeholder.setBackground(VIDEO);content.add(placeholder,BorderLayout.CENTER);
        root.add(content,BorderLayout.CENTER);
        footer=panel(new BorderLayout());footer.setBorder(BorderFactory.createEmptyBorder(3,12,3,12));
        connectionDetail=label(connectionText,11,Font.PLAIN,MUTED);footer.add(connectionDetail,BorderLayout.CENTER);
        storageSummary=button("录像存储","folder",false,0,this::showSettings);
        storageSummary.setName("storage.summary");
        storageSummary.setBorder(BorderFactory.createEmptyBorder(3,9,3,9));
        footer.add(storageSummary,BorderLayout.EAST);
        root.add(footer,BorderLayout.SOUTH);
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"),"leaveFull");
        root.getActionMap().put("leaveFull",new AbstractAction(){public void actionPerformed(ActionEvent e){
            if(fullscreen)toggleFullscreen();else if(isSettingsVisible())setSettingsVisible(false);
        }});
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F11"),"toggleFull");
        root.getActionMap().put("toggleFull",new AbstractAction(){public void actionPerformed(ActionEvent e){toggleFullscreen();}});
        uiTimer=new Timer(500,e->{
            clockLabel.setText(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            refreshActions();restoreDetail();if(settings!=null)settings.refresh();
        });
        if(!GraphicsEnvironment.isHeadless()){
            window=new JFrame(AppIdentity.NAME);window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            window.setContentPane(root);window.setIconImages(AppIdentity.icons());
            Rectangle usable=usableScreen(window.getGraphicsConfiguration());
            window.setMinimumSize(new Dimension(Math.min(920,usable.width),Math.min(640,usable.height)));
            window.setSize(Math.min(1360,usable.width),Math.min(860,usable.height));
            window.setLocation(usable.x+(usable.width-window.getWidth())/2,usable.y+(usable.height-window.getHeight())/2);
            settingsShown=usable.width>=920;
            settingsButton.setSelected(settingsShown && deviceManager==null);
            window.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){listener.onClose();}});
        }
    }
    private JPanel buildHeader(){
        JPanel result=panel(new BorderLayout());
        JPanel title=new JPanel(new BorderLayout());titleBar=title;title.setBackground(TITLE);
        title.setBorder(BorderFactory.createEmptyBorder(0,13,0,13));title.setPreferredSize(new Dimension(1,36));
        JLabel brand=label(AppIdentity.NAME,14,Font.BOLD,new Color(236,238,240));
        brand.setIcon(new BrandIcon(18));brand.setIconTextGap(8);title.add(brand,BorderLayout.WEST);
        JPanel right=new JPanel(new FlowLayout(FlowLayout.RIGHT,18,7));right.setOpaque(false);
        recordLabel=label("● 未录像",12,Font.PLAIN,new Color(170,173,178));
        clockLabel=label("",12,Font.PLAIN,new Color(204,207,211));clockLabel.setName("nvr.clock");clockLabel.setToolTipText("录像机时间（电脑本地时间）");right.add(recordLabel);right.add(clockLabel);
        JButton about=new JButton("关于");
        about.setName("app.about");about.setFont(new Font(FONT_NAME,Font.PLAIN,12));
        about.setForeground(new Color(236,238,240));about.setOpaque(false);
        about.setContentAreaFilled(false);about.setBorder(BorderFactory.createEmptyBorder(0,4,0,4));
        about.setToolTipText("关于录像机 · 版本与作者信息");
        about.addActionListener(e->{
            if(aboutDialog==null || !aboutDialog.isDisplayable())aboutDialog=new AboutDialog(window);
            aboutDialog.setVisible(true);aboutDialog.toFront();aboutDialog.requestFocus();
        });
        right.add(about);title.add(right,BorderLayout.EAST);
        result.add(title,BorderLayout.NORTH);
        JPanel toolbar=panel(new BorderLayout());toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,BORDER),BorderFactory.createEmptyBorder(6,12,6,12)));
        JPanel modes=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));modes.setOpaque(false);
        liveButton=button("实时预览","video",false,98,listener::onLive);
        replayButton=button("录像回放","playback",false,98,listener::onPlayback);
        liveButton.setForeground(RED);liveButton.setSelected(true);
        modes.add(liveButton);modes.add(replayButton);
        cameraTitle=label(cameraName,12,Font.PLAIN,TEXT);cameraTitle.setBorder(BorderFactory.createEmptyBorder(0,6,0,2));
        cameraTitle.setPreferredSize(new Dimension(86,32));
        statusDot=new StatusDot(MUTED);statusLabel=label("预览未连接",11,Font.PLAIN,MUTED);modes.add(statusDot);modes.add(statusLabel);
        // Preview status is separate from recorder-wide status and the action controls.
        modes.remove(statusDot);modes.remove(statusLabel);right.add(statusDot,0);right.add(statusLabel,1);
        toolbar.add(modes,BorderLayout.WEST);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));actions.setOpaque(false);
        recordingButton=button("录制本通道","record",false,104,listener::onRecordingToggle);
        recordingButton.setName("camera.record.toggle");
        startAllRecordingButton=button("全部开始录像","record",true,120,listener::onStartAllRecording);
        startAllRecordingButton.setName("recorder.startAll");
        startAllRecordingButton.setToolTipText("一起录制所有已设置地址的摄像头；本次运行中新添加的摄像头也一起录制");
        stopAllRecordingButton=button("全部停止录像","stop",false,120,listener::onStopAllRecording);
        stopAllRecordingButton.setName("recorder.stopAll");stopAllRecordingButton.setEnabled(false);
        stopAllRecordingButton.setToolTipText("停止全部摄像头录像并保存最后一段；实时预览继续");
        connectButton=button("连接预览","play",false,98,listener::onConnectToggle);
        audioButton=button("声音关","mute",false,76,listener::onAudioToggle);
        snapshotButton=button("抓图","snapshot",false,76,listener::onSnapshot);snapshotButton.setEnabled(false);
        snapshotButton.setToolTipText("保存当前画面到项目的 data\\screenshots 文件夹");
        fullscreenButton=button("画面全屏","fullscreen",false,96,this::toggleFullscreen);
        fullscreenButton.setName("video.fullscreen");
        settingsButton=button("录像机设置","settings",false,112,listener::onSettings);settingsButton.setSelected(true);
        settingsButton.setName("storage.open");
        settingsButton.setToolTipText("设置录像存储、人员识别、远程用户和加密连接");
        for(JButton b:new JButton[]{startAllRecordingButton,stopAllRecordingButton,audioButton,snapshotButton,fullscreenButton,settingsButton})actions.add(b);
        toolbar.add(actions,BorderLayout.EAST);
        JPanel operationRows=panel(new BorderLayout());operationRows.add(toolbar,BorderLayout.NORTH);
        operationRows.add(buildDeviceBar(),BorderLayout.SOUTH);result.add(operationRows,BorderLayout.SOUTH);return result;
    }
    private JPanel buildDeviceBar(){
        JPanel bar=panel(new BorderLayout(6,0));
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,BORDER),BorderFactory.createEmptyBorder(5,12,5,12)));
        JPanel select=panel(new FlowLayout(FlowLayout.LEFT,6,0));
        select.add(label("当前通道",12,Font.PLAIN,TEXT));
        deviceSelector=new JComboBox<>();deviceSelector.setName("devices.select");NvrUi.styleCombo(deviceSelector);
        deviceSelector.setPreferredSize(new Dimension(165,32));
        deviceSelector.getAccessibleContext().setAccessibleName("当前通道");
        deviceSelector.setRenderer(new DefaultListCellRenderer(){
            @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                JLabel item=(JLabel)super.getListCellRendererComponent(list,value,index,selected,focus);
                if(value instanceof DeviceSummary device){item.setText(device.name());item.setToolTipText(device.name()+" · "+device.endpoint());}
                return item;
            }
        });
        deviceSelector.addActionListener(event->{if(!syncingDevices && deviceSelector.getSelectedItem() instanceof DeviceSummary item)listener.onSelectDevice(item.id());});
        select.add(deviceSelector);
        JButton addDevice=button("添加设备","",false,0,listener::onAddDevice);addDevice.setIcon(null);addDevice.setName("devices.add");select.add(addDevice);
        JButton manageDevices=button("设备管理","",false,0,listener::onManageDevices);manageDevices.setIcon(null);manageDevices.setName("devices.manage");select.add(manageDevices);
        JButton remoteAccess=button("远程访问","",false,0,this::showRemoteSettings);remoteAccess.setIcon(null);remoteAccess.setName("remote.open");select.add(remoteAccess);
        bar.add(select,BorderLayout.WEST);
        JPanel views=panel(new FlowLayout(FlowLayout.RIGHT,5,0));
        views.add(label("实时分屏",12,Font.PLAIN,TEXT));
        layoutSelector=new JComboBox<>(new String[]{"单画面","四分屏","九分屏"});layoutSelector.setName("devices.layout");NvrUi.styleCombo(layoutSelector);
        layoutSelector.setPreferredSize(new Dimension(90,32));
        layoutSelector.addActionListener(event->{if(syncingLayout)return;int tiles=layoutSelector.getSelectedIndex()==0?1:layoutSelector.getSelectedIndex()==1?4:9;
            expandedGridLayout=0;
            setGridLayout(tiles);listener.onLayoutChanged(tiles);});
        views.add(layoutSelector);
        previousGridPage=button("‹","",false,0,()->grid.changePage(-1));previousGridPage.setIcon(null);previousGridPage.setName("devices.page.previous");previousGridPage.setToolTipText("上一组摄像头");
        nextGridPage=button("›","",false,0,()->grid.changePage(1));nextGridPage.setIcon(null);nextGridPage.setName("devices.page.next");nextGridPage.setToolTipText("下一组摄像头");
        gridPage=label("1 / 1",11,Font.PLAIN,MUTED);gridPage.setPreferredSize(new Dimension(40,32));gridPage.setHorizontalAlignment(SwingConstants.CENTER);
        views.add(previousGridPage);views.add(gridPage);views.add(nextGridPage);bar.add(views,BorderLayout.EAST);
        grid.setViewListener(this::refreshGridControls);refreshGridControls();return bar;
    }
    private void refreshGridControls(){
        if(gridPage==null)return;
        gridPage.setText((grid.pageIndex()+1)+" / "+grid.pageCount());
        previousGridPage.setEnabled(!replayMode&&grid.pageIndex()>0);
        nextGridPage.setEnabled(!replayMode&&grid.pageIndex()+1<grid.pageCount());
    }
    private void toggleChannelExpansion(String id){
        listener.onSelectDevice(id);
        if(fullscreen){toggleFullscreen();return;}
        int layout;
        if(grid.tileCount()>1){expandedGridLayout=grid.tileCount();layout=1;}
        else if(expandedGridLayout>1){layout=expandedGridLayout;expandedGridLayout=0;}
        else if(devices.size()>1){layout=devices.size()<=4?4:9;}
        else{toggleFullscreen();return;}
        setGridLayout(layout);listener.onLayoutChanged(layout);
    }
    private void refreshActions(){
        boolean audio=replayMode&&playback!=null?playback.isAudioEnabled():liveAudio;
        audioButton.setText(audio?"声音开":"声音关");
        audioButton.setIcon(new LineIcon(audio?"sound":"mute",16));audioButton.setForeground(audio?RED:TEXT);
        audioButton.setEnabled(replayMode?playback!=null&&playback.isSourceAvailable():state.equals("CONNECTED"));
        audioButton.setToolTipText((replayMode?"回放声音":"实时声音")+(audio?"已开，点击关闭":"已关，点击开启"));
        snapshotButton.setEnabled(currentFrame()!=null);
    }
    private void restoreDetail(){if(System.currentTimeMillis()<noticeExpires)return;
        String text=replayMode?"回放 · "+(playback==null?"":playback.playbackDetail()):state.equals("CONNECTED")?"实时预览 · 拖动时间轴即可回看，松开后继续播放":connectionText;
        connectionDetail.setText(text);connectionDetail.setToolTipText(text);connectionDetail.setForeground(MUTED);
    }
    public void toggleFullscreen(){onEdt(()->{
        if(window==null)return;
        if(!fullscreen){normalBounds=window.getBounds();normalState=window.getExtendedState();settingsBeforeFullscreen=settingsShown;fullscreen=true;
            header.setVisible(false);footer.setVisible(false);setSettingsVisible(false);
            if(playback!=null)playback.setVideoFullscreen(true);
            window.dispose();window.setUndecorated(true);window.setExtendedState(JFrame.NORMAL);
            window.setBounds(window.getGraphicsConfiguration().getBounds());window.setVisible(true);
        }else{fullscreen=false;window.dispose();window.setUndecorated(false);header.setVisible(true);footer.setVisible(true);
            if(playback!=null)playback.setVideoFullscreen(false);
            setSettingsVisible(settingsBeforeFullscreen);window.setBounds(normalBounds);window.setExtendedState(normalState);window.setVisible(true);
        }fullscreenButton.setText(fullscreen?"退出全屏":"画面全屏");
        fullscreenButton.setToolTipText(fullscreen?"返回普通窗口，也可以按 Esc":"仅画面全屏，保留回放控制；按 Esc 或双击画面返回");
        root.revalidate();root.repaint();
    });}
    private static JPanel panel(java.awt.LayoutManager layout){var p=new JPanel(layout);p.setBackground(SURFACE);return p;}
    private static JLabel label(String text,int size,int style,Color color){var l=new JLabel(text);l.setFont(new Font(FONT_NAME,style,size));l.setForeground(color);return l;}
    private static JButton button(String text,String icon,boolean primary,int width,Runnable action){
        return NvrUi.button(text,new LineIcon(icon,16),primary,action);
    }
    private static Rectangle usableScreen(GraphicsConfiguration configuration){
        Rectangle b=new Rectangle(configuration.getBounds());Insets i=Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        b.x+=i.left;b.y+=i.top;b.width=Math.max(1,b.width-i.left-i.right);b.height=Math.max(1,b.height-i.top-i.bottom);return b;
    }
    private static String publicEndpoint(String endpoint){try{var u=URI.create(endpoint.contains("://")?endpoint:"rtsp://"+endpoint);return u.getHost()==null?"尚未配置":u.getHost()+(u.getPort()>0?":"+u.getPort():"");}catch(Exception ignored){return "尚未配置";}}
    private static String redact(String text){return text.replaceAll("(?i)(rtsp|rtsps|http|https)://[^\\s/]*@","$1://***@");}
    private static void onEdt(Runnable task){if(SwingUtilities.isEventDispatchThread())task.run();else SwingUtilities.invokeLater(task);}
    private static void onEdtAndWait(Runnable task){if(SwingUtilities.isEventDispatchThread()){task.run();return;}try{SwingUtilities.invokeAndWait(task);}catch(Exception e){throw new IllegalStateException(e);}}
    private static void layoutRecursively(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layoutRecursively(nested);}
    private static BufferedImage appIcon(){var image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setColor(RED);g.fillRect(0,0,64,64);g.setColor(Color.WHITE);new LineIcon("video",42).paintIcon(null,g,11,11);g.dispose();return image;}

    private static final class FlatButton extends JButton {
        private final boolean primary;
        private boolean dark;
        FlatButton(String title, boolean primary) {
            super(title);
            this.primary = primary;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            Color fill = dark ? TITLE : (primary ? RED : Color.WHITE);
            if (isSelected() && !primary && !dark) fill = new Color(252, 237, 238);
            if (getModel().isPressed()) fill = primary ? RED.darker() : new Color(226, 228, 231);
            else if (getModel().isRollover() && isEnabled()) fill = dark ? new Color(66, 69, 74)
                    : primary ? new Color(188, 35, 40) : new Color(241, 242, 244);
            if (!isEnabled()) fill = new Color(244, 245, 246);
            g.setColor(fill);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (!dark) {
                g.setColor(primary ? fill : (isSelected() ? new Color(226, 170, 172) : BORDER));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
            if (isFocusOwner()) {
                g.setColor(primary ? Color.WHITE : RED);
                g.drawRect(2, 2, getWidth() - 5, getHeight() - 5);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class StatusDot extends JComponent {
        private Color color;
        StatusDot(Color color) {
            this.color = color;
            Dimension size = new Dimension(8, 8);
            setPreferredSize(size);
            setMaximumSize(size);
            setMinimumSize(size);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(color);
            g.fillOval(1, 1, 6, 6);
            g.dispose();
        }
    }

    private static final class BrandIcon implements javax.swing.Icon {
        private final int size;
        BrandIcon(int size) { this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(new Color(229, 68, 73));
            new LineIcon("video", size).paintIcon(null, g, x, y);
            g.dispose();
        }
    }

    private static final class LineIcon implements javax.swing.Icon {
        private final String type;
        private final int size;
        LineIcon(String type, int size) { this.type = type; this.size = size; }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.translate(x, y);
            g.scale(size / 24.0, size / 24.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (c != null) g.setColor(c.isEnabled() ? c.getForeground() : new Color(174, 177, 182));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (type) {
                case "video" -> {
                    g.drawRect(2, 6, 13, 12);
                    g.drawPolyline(new int[] {15, 22, 22, 15}, new int[] {10, 6, 18, 14}, 4);
                }
                case "play" -> g.drawPolygon(new int[] {7, 18, 7}, new int[] {4, 12, 20}, 3);
                case "playback" -> {
                    g.drawRect(2, 4, 20, 16);
                    g.drawPolygon(new int[] {9, 15, 9}, new int[] {8, 12, 16}, 3);
                }
                case "mute", "sound" -> {
                    g.drawPolyline(new int[] {10, 6, 2, 2, 6, 10, 10}, new int[] {5, 9, 9, 15, 15, 19, 5}, 7);
                    if (type.equals("mute")) {
                        g.drawLine(16, 9, 22, 15);
                        g.drawLine(22, 9, 16, 15);
                    } else {
                        g.drawArc(8, 7, 9, 10, -55, 110);
                        g.drawArc(8, 3, 15, 18, -55, 110);
                    }
                }
                case "snapshot" -> {
                    g.drawRect(2, 6, 20, 15);
                    g.drawPolyline(new int[] {7, 9, 15, 17}, new int[] {6, 3, 3, 6}, 4);
                    g.drawOval(8, 10, 8, 8);
                }
                case "record" -> {
                    g.drawOval(3, 3, 18, 18);
                    g.fillOval(8, 8, 8, 8);
                }
                case "stop" -> g.fillRect(5, 5, 14, 14);
                case "fullscreen" -> {
                    g.drawPolyline(new int[] {3, 3, 9}, new int[] {9, 3, 3}, 3);
                    g.drawPolyline(new int[] {15, 21, 21}, new int[] {3, 3, 9}, 3);
                    g.drawPolyline(new int[] {3, 3, 9}, new int[] {15, 21, 21}, 3);
                    g.drawPolyline(new int[] {15, 21, 21}, new int[] {21, 21, 15}, 3);
                }
                case "folder" -> {
                    g.drawPolyline(new int[] {2, 2, 9, 12, 22, 22, 2}, new int[] {20, 4, 4, 7, 7, 20, 20}, 7);
                    g.drawLine(2, 10, 22, 10);
                }
                case "settings" -> {
                    g.drawOval(5, 5, 14, 14);
                    g.drawOval(9, 9, 6, 6);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        g.drawLine((int) (12 + 7 * Math.cos(a)), (int) (12 + 7 * Math.sin(a)),
                                (int) (12 + 10 * Math.cos(a)), (int) (12 + 10 * Math.sin(a)));
                    }
                }
                default -> g.drawOval(4, 4, 16, 16);
            }
            g.dispose();
        }
    }
}
