package com.apex.maptool.ui;

import com.apex.maptool.unity.HaoQuang;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tab <b>Hào Quang</b> — phần CĂN CHỈNH: kéo aura cho khớp nhân vật rồi <b>ghi thẳng vào client</b>.
 * Bản Java của tab "Hào Quang" trong {@code tools/honma-composer/player_viewer.py}.
 *
 * <h2>Bố cục (bản "UI Redesign")</h2>
 * Rail phải chia thành <b>4 bước có số</b> đúng thứ tự làm việc, bước xong thì gập lại kèm tóm tắt:
 * <pre>
 *  hàng phạm vi   [Hào quang SM | Đặc biệt] + chip "đang sửa: …"
 *  1 · Nguồn ảnh          Import · Tạo mới (PNG) · Sửa import
 *  2 · Hai lớp ảnh        lớp SAU (slot 0) + công tắc lớp TRƯỚC (slot 1)
 *  3 · Canh chỉnh & áp    [Lớp SAU | Lớp TRƯỚC | Cả 2 lớp] · Scale/Offset (slider + Ô SỐ) · Áp
 *  4 · Nâng cao           thông số + đường dẫn game + đổi cỡ theo hệ số tự do
 *  ─────────── chân ghim: "Tạo / cập nhật cho …" + dòng đường dẫn ghi vào game
 * </pre>
 * Nhóm <i>Xem thử</i> (Chạy · FPS · Lặp · Nằm SAU player) và 2 công tắc ẩn/hiện lớp KHÔNG còn nằm
 * ở đây — chúng được {@link PlayerViewerFrame} mượn lên thanh khung xem / góc khung xem
 * ({@link #previewButton()}, {@link #chipBack()}…). Widget vẫn là widget gốc nên listener giữ nguyên.
 *
 * <h2>Căn chỉnh đi vào đâu</h2>
 * <table>
 *   <tr><th>Thanh kéo</th><th>Ghi vào</th><th>Ý nghĩa</th></tr>
 *   <tr><td>Scale</td><td>{@code spritePixelsToUnits} (PPU) của MỌI frame</td>
 *       <td>{@code PPU_mới = PPU / hệ_số} — PPU nhỏ hơn thì sprite to hơn</td></tr>
 *   <tr><td>Offset X/Y (kéo chuột)</td><td>{@code spritePivot} + {@code alignment: 9}</td>
 *       <td>{@code px = px − ox·k·PPU/w} · {@code py = py + oy·k·PPU/h}, {@code k = importScale × 0.7}</td></tr>
 * </table>
 * Áp xong thì thanh kéo tự về mốc (Scale→1, Offset→0) mà <b>ảnh xem trước KHÔNG nhảy</b>, vì xem
 * trước đọc PPU/pivot mới — nhìn thấy sao thì trong game đúng vậy.
 *
 * <h2>An toàn</h2>
 * Mọi thay đổi được dựng TRONG RAM trước ({@link HaoQuang} thuần văn bản); hỏng ở bước nào thì chưa
 * file nào bị đụng. File mã nguồn/asset bị sửa đều được <b>backup ra ngoài {@code Assets/}</b>
 * (thư mục {@code .haoquang_backups} cạnh tool) — để trong {@code Assets/} thì Unity import cả .bak
 * thành rác.
 */
public final class HaoQuangPanel extends JPanel {

    /** Cầu nối sang Player Viewer (khung xem + nhân vật đang load). */
    public interface Host {
        /** {@code scale} của SkeletonData.asset nhân vật đang xem; null = chưa load nhân vật. */
        Float importScale();
        /** px-màn-hình trên 1 đơn vị skeleton (dùng quy offset kéo chuột ra đơn vị game). */
        double unitPerSkeleton();
        /** px-màn-hình trên 1 world unit của Unity (dùng tính cỡ aura theo PPU). */
        double pxPerWorldUnit();
        /** Thư mục Spine của nhân vật đang xem (để dò đường trong client). */
        Path currentSpineFolder();
        void repaintCanvas();
    }

    /** Đối tượng đang canh chỉnh ở bước 3. */
    private static final int T_BACK = 0, T_FRONT = 1, T_BOTH = 2;

    // ═══════════ trạng thái mà canvas ĐỌC để vẽ overlay ═══════════
    /** Đang ở chế độ xem hào quang (tab đang mở + đã nạp frame). */
    public boolean active;
    public boolean twoLayer, behind = true, hideBack, hideFront;
    public double scaleBoth = 1, backScale = 1, frontScale = 1;
    public double dx, dy, fdx, fdy;
    public double[] backPivot = {0.5, 0.14}, frontPivot = {0.5, 0.14};
    public Double backPpu, frontPpu;
    /** "sau" hoặc "truoc" — lớp mà kéo chuột sẽ dời. */
    public String adjustLayer = "sau";

    private final Host host;
    private final List<BufferedImage> backImgs = new ArrayList<>();
    private final List<BufferedImage> frontImgs = new ArrayList<>();
    private List<HaoQuang.Frame> backFrames = new ArrayList<>(), frontFrames = new ArrayList<>();
    private Path backFolder, frontFolder, texDir;
    private int frameIdx;
    private boolean playing;
    private final Timer previewTimer = new Timer(66, e -> tickPreview());

    /** "sm" · "db". */
    private String mode = "sm";

    // ═══════════ hàng phạm vi ═══════════
    private final Theme.Segmented segScope =
            new Theme.Segmented(36, 30, true, true, "Hào quang SM", "Đặc biệt");
    private final Theme.Chip chScope = new Theme.Chip("đang sửa: SM", Theme.ACCENT, 30, 12, false);

    // ═══════════ bước 1 ═══════════
    private final Theme.StepCard step1 = new Theme.StepCard(1, "Nguồn ảnh", true);
    private final JButton btImport = Theme.ghost("Import SM…", 34, null);

    // ═══════════ bước 2 ═══════════
    private final Theme.StepCard step2 = new Theme.StepCard(2, "Hai lớp ảnh", true);
    private final JComboBox<String> cbBack = new JComboBox<>();
    private final JComboBox<String> cbFront = new JComboBox<>();
    private final JLabel lbBackInfo = Theme.monoLabel("(chưa đọc lớp sau)", 12, Theme.TEXT_DIM);
    private final JLabel lbFrontInfo = Theme.label("tắt — bật công tắc để chọn ảnh lớp trước",
            12, Font.PLAIN, Theme.TEXT_DIM);
    private final Theme.Toggle tgTwo = new Theme.Toggle(false);
    private JLabel lbFrontName;
    private JButton btFrontPick;

    // ═══════════ bước 3 ═══════════
    private final Theme.StepCard step3 = new Theme.StepCard(3, "Canh chỉnh & áp vào game", true);
    private final Theme.Segmented segTarget =
            new Theme.Segmented(34, 28, false, true, "Lớp SAU", "Lớp TRƯỚC", "Cả 2 lớp");
    private final JSlider slScale = new JSlider(10, 400, 100);
    private final JSpinner spScale = scaleSpin();
    private final JSlider slX = new JSlider(-700, 700, 0);
    private final JSpinner spX = Theme.spin(0, -700, 700);
    private final JSlider slY = new JSlider(-700, 700, 0);
    private final JSpinner spY = Theme.spin(0, -700, 700);
    private final JLabel lbPpu = Theme.monoLabel("PPU —", 12, Theme.TEXT_DIM);
    private JButton btApplyPos;
    private JPanel rowX, rowY;
    private int target = T_BACK;

    // ═══════════ bước 4 ═══════════
    private final Theme.StepCard step4 = new Theme.StepCard(4, "Nâng cao", false);
    private final JTextField txClip = new JTextField();
    private final JTextField txTrigger = new JTextField();
    private final JTextField txEnum = new JTextField();
    private final JTextField txFrontClip = new JTextField("aniHaoQuangTruoc");
    private final JTextField txFrontTrigger = new JTextField("haoquangtruoc");
    private final JCheckBox chkDoController = new JCheckBox("Thêm vào HaoQuang.controller", true);
    private final JCheckBox chkDoCode = new JCheckBox("Thêm Enum + case trong ActorVisual", true);
    private final JTextField txOutDir = new JTextField();
    private final JTextField txController = new JTextField();
    private final JTextField txEnumFile = new JTextField();
    private final JTextField txEnumDbFile = new JTextField();
    private final JTextField txActor = new JTextField();
    private final JTextField txPrefab = new JTextField();
    private final JSpinner spFactor = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 10.0, 0.1));
    private final JLabel lbAdvEnum = Theme.monoLabel("Enum_HaoQuang", 12, Theme.TEXT_2);
    private final JLabel lbAdvAnim = Theme.monoLabel("PlayHaoQuangAnim", 12, Theme.TEXT_2);
    private final JLabel lbAdvPrefab = Theme.monoLabel("HaoQuang2", 12, Theme.TEXT_2);

    // ═══════════ widget MƯỢN lên thanh khung xem ═══════════
    private final JButton btPlay = Theme.tint("Chạy", Theme.GREEN, 30, null);
    private final JSpinner spFps = fpsSpin();
    private final JCheckBox chkLoop = new JCheckBox("Lặp", true);
    private final Theme.ChipButton chBehind = new Theme.ChipButton("Nằm SAU player", true, 30, null);
    private final Theme.ChipButton chLayerBack = new Theme.ChipButton("hiện lớp SAU", true, 30, null);
    private final Theme.ChipButton chLayerFront = new Theme.ChipButton("lớp TRƯỚC · tắt", false, 30, null);

    // ═══════════ chân rail ═══════════
    private final JButton btCreate = Theme.primary("Tạo / cập nhật cho SM", 40, null);
    private final JLabel lbNote = Theme.monoLabel("ghi vào Enum_HaoQuang · PlayHaoQuangAnim · HaoQuang2",
            12, Theme.TEXT_DIM);

    private boolean suspend;

    public HaoQuangPanel(Host host) {
        this.host = host;
        setLayout(new BorderLayout());
        setBackground(Theme.BG_SURFACE);

        add(buildScopeRow(), BorderLayout.NORTH);

        JPanel steps = Theme.colBox();
        steps.setBorder(new EmptyBorder(8, 8, 8, 8));
        buildStep1();
        buildStep2();
        buildStep3();
        buildStep4();
        steps.add(step1);
        steps.add(Box.createVerticalStrut(6));
        steps.add(step2);
        steps.add(Box.createVerticalStrut(6));
        steps.add(step3);
        steps.add(Box.createVerticalStrut(6));
        steps.add(step4);
        steps.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(steps,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        add(sp, BorderLayout.CENTER);

        add(buildFooter(), BorderLayout.SOUTH);

        wire();
        setMode("sm");
        setTarget(T_BACK);
        loadCalib();
    }

    // ═══════════════════════ DỰNG UI ═══════════════════════

    private JComponent buildScopeRow() {
        JPanel p = Theme.row();
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                new EmptyBorder(8, 12, 8, 12)));
        p.setPreferredSize(new Dimension(10, 46));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        p.add(segScope);
        p.add(Box.createHorizontalStrut(10));
        p.add(chScope);
        return p;
    }

    private void buildStep1() {
        JPanel g = new JPanel(new GridLayout(1, 3, 8, 0));
        g.setOpaque(false);
        g.setAlignmentX(LEFT_ALIGNMENT);
        g.add(btImport);
        g.add(Theme.ghost("Tạo mới (PNG)", 34, e -> newAura()));
        JButton bFix = Theme.ghost("Sửa import", 34, e -> fixImport());
        bFix.setToolTipText("Đưa mọi frame về Sprite Single (giữ guid) — sửa lỗi 'game không hiện hào quang'");
        g.add(bFix);
        g.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        step1.body().add(g);
    }

    private void buildStep2() {
        step2.setSummary("slot 0 = sau · slot 1 = trước");

        // ── lớp SAU ──
        JPanel r1 = Theme.row();
        JPanel dot = new JPanel();
        dot.setBackground(Theme.PURPLE);
        Theme.lockSize(dot, 7, 7);
        r1.add(dot);
        r1.add(Box.createHorizontalStrut(8));
        // 7 (chấm) + 8 + 96 = 111 — trùng đúng lề thụt của dòng trạng thái bên dưới.
        JLabel l1 = Theme.label("Lớp SAU", 12, Font.BOLD, Theme.TEXT_2);
        Theme.lockSize(l1, 96, 22);
        r1.add(l1);
        r1.add(Box.createHorizontalStrut(8));
        cbBack.setFont(Theme.mono(13, Font.PLAIN));
        cbBack.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        cbBack.setPreferredSize(new Dimension(120, 32));
        r1.add(cbBack);
        r1.add(Box.createHorizontalStrut(8));
        r1.add(Theme.iconButton(Theme.icon(Theme.IC_MORE, 14, Theme.TEXT_MUTED), 36, 32,
                "Chọn thư mục ảnh lớp SAU", e -> pickBackFolder()));
        r1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        step2.body().add(r1);
        step2.body().add(Box.createVerticalStrut(9));

        lbBackInfo.setBorder(new EmptyBorder(0, 111, 0, 0));
        lbBackInfo.setAlignmentX(LEFT_ALIGNMENT);
        step2.body().add(Theme.capH(lbBackInfo));
        step2.body().add(Box.createVerticalStrut(9));
        step2.body().add(Theme.hr());
        step2.body().add(Box.createVerticalStrut(9));

        // ── lớp TRƯỚC ──
        JPanel r2 = Theme.row();
        r2.add(tgTwo);
        r2.add(Box.createHorizontalStrut(8));
        // 30 (công tắc) + 8 + 73 = 111 — khớp cột combo với hàng lớp SAU.
        lbFrontName = Theme.label("Lớp TRƯỚC", 12, Font.BOLD, Theme.TEXT_MUTED);
        Theme.lockSize(lbFrontName, 73, 22);
        r2.add(lbFrontName);
        r2.add(Box.createHorizontalStrut(8));
        cbFront.setFont(Theme.mono(13, Font.PLAIN));
        cbFront.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        cbFront.setPreferredSize(new Dimension(120, 32));
        r2.add(cbFront);
        r2.add(Box.createHorizontalStrut(8));
        btFrontPick = Theme.iconButton(Theme.icon(Theme.IC_MORE, 14, Theme.TEXT_MUTED), 36, 32,
                "Chọn thư mục ảnh lớp TRƯỚC", e -> pickFrontFolder());
        r2.add(btFrontPick);
        r2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        step2.body().add(r2);
        step2.body().add(Box.createVerticalStrut(9));

        lbFrontInfo.setBorder(new EmptyBorder(0, 112, 0, 0));
        lbFrontInfo.setAlignmentX(LEFT_ALIGNMENT);
        step2.body().add(Theme.capH(lbFrontInfo));
        updateFrontEnabled();
    }

    private void buildStep3() {
        step3.setSummary("ghi .meta của từng lớp");

        JPanel r0 = Theme.row();
        JLabel l = Theme.label("Đang chỉnh", 13, Font.PLAIN, Theme.TEXT_MUTED);
        r0.add(Theme.lock(l));
        r0.add(Box.createHorizontalStrut(10));
        r0.add(segTarget);
        r0.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        step3.body().add(r0);
        step3.body().add(Box.createVerticalStrut(9));

        // lưới 3 cột: nhãn 64 | slider | ô số 78
        JPanel grid = Theme.colBox();
        // Ô Scale rộng hơn 78 của bản thiết kế vì Swing còn phải chừa chỗ cho 2 mũi tên spinner
        // + hậu tố "×" — giữ 78 là "1,00" bị cắt thành "1,0".
        grid.add(sliderRow("Scale", slScale, numBox(spScale, "×", 94)));
        grid.add(Box.createVerticalStrut(6));
        rowX = sliderRow("Offset X", slX, numBox(spX, null, 78));
        grid.add(rowX);
        grid.add(Box.createVerticalStrut(6));
        rowY = sliderRow("Offset Y", slY, numBox(spY, null, 78));
        grid.add(rowY);
        step3.body().add(grid);
        step3.body().add(Box.createVerticalStrut(9));

        // hàng chip nhanh + PPU
        JPanel q = Theme.row();
        q.add(Theme.lock(Theme.label("Nhanh", 12, Font.PLAIN, Theme.TEXT_MUTED)));
        q.add(Box.createHorizontalStrut(8));
        for (double f : new double[]{0.5, 0.75, 1, 1.5, 2}) {
            Theme.ChipButton c = new Theme.ChipButton("×" + HaoQuang.fmt(f), false, 24,
                    e -> setScaleValue(f));
            c.setMonoFont();
            c.setToolTipText("Đặt Scale = " + HaoQuang.fmt(f) + "× rồi bấm \"Áp SCALE\" để ghi vào game");
            q.add(c);
            q.add(Box.createHorizontalStrut(6));
        }
        q.add(Box.createHorizontalGlue());
        q.add(lbPpu);
        q.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        step3.body().add(q);
        step3.body().add(Box.createVerticalStrut(9));

        JPanel btns = new JPanel(new GridLayout(1, 3, 8, 0));
        btns.setOpaque(false);
        btns.setAlignmentX(LEFT_ALIGNMENT);
        btns.add(Theme.tint("Áp SCALE", Theme.BLUE, 34, e -> applyScaleCurrent()));
        btApplyPos = Theme.tint("Áp VỊ TRÍ", Theme.BLUE, 34, e -> applyOffsetLayer(layerKey()));
        btns.add(btApplyPos);
        btns.add(Theme.ghost("Reset", 34, e -> resetCurrent()));
        btns.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        step3.body().add(btns);
    }

    private void buildStep4() {
        step4.setSummary("thông số + đường dẫn game");
        JPanel b = step4.body();
        b.add(advRow("Enum", lbAdvEnum));
        b.add(Box.createVerticalStrut(8));
        b.add(advRow("Anim script", lbAdvAnim));
        b.add(Box.createVerticalStrut(8));
        b.add(advRow("Prefab", lbAdvPrefab));
        b.add(Box.createVerticalStrut(10));
        b.add(Theme.hr());
        b.add(Box.createVerticalStrut(10));

        b.add(Theme.capH(Theme.sectionHeader("Thông số", Theme.TEXT_MUTED)));
        b.add(Box.createVerticalStrut(6));
        b.add(fieldRow("Tên clip (.anim)", txClip));
        b.add(fieldRow("Trigger", txTrigger));
        b.add(fieldRow("Enum (tên)", txEnum));
        b.add(fieldRow("Clip lớp trước", txFrontClip));
        b.add(fieldRow("Trigger lớp trước", txFrontTrigger));
        b.add(Box.createVerticalStrut(10));

        b.add(Theme.capH(Theme.sectionHeader("Đổi cỡ trong game theo hệ số tự do", Theme.TEXT_MUTED)));
        b.add(Box.createVerticalStrut(6));
        JPanel gs = Theme.row();
        gs.add(Theme.lock(Theme.label("Hệ số ×", 13, Font.PLAIN, Theme.TEXT_2)));
        gs.add(Box.createHorizontalStrut(8));
        gs.add(Theme.lockSize(Theme.monoSpin(spFactor, 84, 32), 84, 32));
        gs.add(Box.createHorizontalStrut(8));
        JButton bp = Theme.ghost("Áp PPU ngay", 32,
                e -> applyGameSize(layerKey(), ((Number) spFactor.getValue()).doubleValue()));
        bp.setToolTipText("Ghi thẳng PPU của lớp đang chỉnh theo hệ số này (không qua thanh Scale)");
        gs.add(Theme.lockH(bp, 32));
        gs.add(Box.createHorizontalGlue());
        gs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        b.add(gs);
        b.add(Box.createVerticalStrut(10));

        b.add(Theme.capH(Theme.sectionHeader("Cập nhật game (tự backup)", Theme.TEXT_MUTED)));
        b.add(Box.createVerticalStrut(6));
        for (JCheckBox c : new JCheckBox[]{chkDoController, chkDoCode}) {
            c.setOpaque(false);
            c.setForeground(Theme.TEXT_2);
            c.setFont(Theme.font(13, Font.PLAIN));
            c.setIconTextGap(8);
            c.setFocusable(false);
            c.setAlignmentX(LEFT_ALIGNMENT);
            b.add(Theme.capH(c));
            b.add(Box.createVerticalStrut(4));
        }
        b.add(fieldRow("Xuất .anim vào", txOutDir));
        b.add(fieldRow("controller", txController));
        b.add(fieldRow("Enum_HaoQuang.cs", txEnumFile));
        b.add(fieldRow("Enum_…DacBiet.cs", txEnumDbFile));
        b.add(fieldRow("ActorVisual.cs", txActor));
        b.add(fieldRow("Player.prefab", txPrefab));
    }

    private JComponent buildFooter() {
        JPanel p = Theme.colBox();
        p.setOpaque(true);
        p.setBackground(Theme.BG_SURFACE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                new EmptyBorder(10, 12, 10, 12)));
        btCreate.setFont(Theme.font(15, Font.BOLD));
        btCreate.setAlignmentX(LEFT_ALIGNMENT);
        btCreate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        btCreate.setPreferredSize(new Dimension(10, 40));
        p.add(btCreate);
        p.add(Box.createVerticalStrut(6));
        lbNote.setHorizontalAlignment(SwingConstants.CENTER);
        lbNote.setAlignmentX(LEFT_ALIGNMENT);
        lbNote.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        p.add(lbNote);
        return p;
    }

    // ── helper dựng widget ──

    /** Hàng "nhãn 64 | slider | ô số 78" — mọi slider PHẢI có ô số nhập tay (handoff §6.6). */
    private static JPanel sliderRow(String label, JSlider s, JComponent numBox) {
        JPanel r = Theme.row();
        JLabel l = Theme.label(label, 13, Font.PLAIN, Theme.TEXT_2);
        Theme.lockSize(l, 64, 22);
        r.add(l);
        r.add(Box.createHorizontalStrut(10));
        s.setOpaque(false);
        s.setFocusable(false);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        s.setPreferredSize(new Dimension(80, 22));
        r.add(s);
        r.add(Box.createHorizontalStrut(10));
        r.add(numBox);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        return r;
    }

    /** Ô số bo góc BG_INPUT (chữ monospace canh phải) kèm hậu tố tuỳ chọn ("×"). */
    private static JComponent numBox(JSpinner sp, String suffix, int w) {
        JPanel p = Theme.card(Theme.BG_INPUT, Theme.BORDER, 7);
        p.setLayout(new BorderLayout(2, 0));
        p.setBorder(new EmptyBorder(0, 8, 0, 8));
        Theme.stripField(sp);
        if (sp.getEditor() instanceof JSpinner.DefaultEditor ed)
            ed.getTextField().setHorizontalAlignment(SwingConstants.RIGHT);
        p.add(sp, BorderLayout.CENTER);
        if (suffix != null) {
            JLabel s = Theme.monoLabel(suffix, 13, Theme.TEXT_DIM);
            p.add(s, BorderLayout.EAST);
        }
        return Theme.lockSize(p, w, 30);
    }

    private static JComponent advRow(String label, JLabel value) {
        JPanel r = Theme.row();
        JLabel l = Theme.label(label, 12, Font.PLAIN, Theme.TEXT_MUTED);
        Theme.lockSize(l, 96, 18);
        r.add(l);
        r.add(Box.createHorizontalStrut(10));
        r.add(value);
        r.add(Box.createHorizontalGlue());
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
        return r;
    }

    private static JComponent fieldRow(String label, JTextField f) {
        JPanel r = Theme.row();
        JLabel l = Theme.label(label, 12, Font.PLAIN, Theme.TEXT_MUTED);
        Theme.lockSize(l, 130, 24);
        r.add(l);
        r.add(Box.createHorizontalStrut(8));
        f.setFont(Theme.mono(12, Font.PLAIN));
        f.setPreferredSize(new Dimension(80, 28));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        r.add(f);
        r.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        r.setBorder(new EmptyBorder(0, 0, 5, 0));
        return r;
    }

    /** Ô số Scale — luôn 2 chữ số thập phân ("0.67") như bản thiết kế. */
    private static JSpinner scaleSpin() {
        JSpinner sp = new JSpinner(new SpinnerNumberModel(1.00, 0.10, 4.00, 0.05));
        JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, "0.00");
        sp.setEditor(ed);
        if (ed.getTextField().getFormatter() instanceof javax.swing.text.DefaultFormatter df)
            df.setCommitsOnValidEdit(true);
        return sp;
    }

    /** Spinner FPS hiện luôn đơn vị ("15 FPS") như bản thiết kế. */
    private static JSpinner fpsSpin() {
        JSpinner sp = Theme.spin(15, 1, 60);
        try {
            JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, "0 'FPS'");
            sp.setEditor(ed);
            if (ed.getTextField().getFormatter() instanceof javax.swing.text.DefaultFormatter df)
                df.setCommitsOnValidEdit(true);
        } catch (Exception ignored) { }
        if (sp.getEditor() instanceof JSpinner.DefaultEditor ed2) {
            ed2.getTextField().setFont(Theme.mono(13, Font.PLAIN));
            ed2.getTextField().setHorizontalAlignment(SwingConstants.RIGHT);
        }
        sp.setToolTipText("Số khung hình/giây khi xem thử và khi ghi clip .anim");
        return sp;
    }

    // ═══════════════════════ WIDGET CHO THANH KHUNG XEM ═══════════════════════

    /** Nút Chạy/Dừng xem thử — {@link PlayerViewerFrame} mượn lên thanh khung xem. */
    public JButton previewButton() { return btPlay; }
    public JSpinner fpsSpinner() { return spFps; }
    public JCheckBox loopCheck() { return chkLoop; }
    /** Chip bật/tắt "Hào quang nằm SAU player (như game)". */
    public Theme.ChipButton behindChip() { return chBehind; }
    /** Chip công tắc ẩn/hiện LỚP SAU (góc dưới trái khung xem). */
    public Theme.ChipButton chipBack() { return chLayerBack; }
    /** Chip công tắc ẩn/hiện LỚP TRƯỚC. */
    public Theme.ChipButton chipFront() { return chLayerFront; }

    // ═══════════════════════ SỰ KIỆN ═══════════════════════
    private void wire() {
        segScope.onChange(i -> setMode(i == 1 ? "db" : "sm"));
        segTarget.onChange(this::setTarget);

        btImport.addActionListener(e -> importExisting("db".equals(mode)));
        btCreate.addActionListener(e -> doExport("db".equals(mode)));
        btPlay.addActionListener(e -> togglePreview());
        btPlay.setIcon(Theme.icon(Theme.IC_PLAY, 11, Theme.GREEN));
        btPlay.setIconTextGap(7);
        chkLoop.setOpaque(false);
        chkLoop.setForeground(Theme.TEXT_2);
        chkLoop.setFont(Theme.font(13, Font.PLAIN));
        chkLoop.setIconTextGap(8);
        chkLoop.setFocusable(false);

        chBehind.setToolTipText("Bật = hào quang vẽ SAU nhân vật (đúng như game)");
        chBehind.addActionListener(e -> {
            chBehind.setOn(!chBehind.isOn());
            behind = chBehind.isOn();
            host.repaintCanvas();
        });
        chLayerBack.setDot(Theme.PURPLE);
        chLayerBack.setOnTint(Theme.PURPLE);
        chLayerBack.setMonoFont();
        chLayerBack.setToolTipText("Bấm = ẩn/hiện lớp SAU trên khung xem");
        chLayerBack.addActionListener(e -> {
            chLayerBack.setOn(!chLayerBack.isOn());
            hideBack = !chLayerBack.isOn();
            updateLayerChips();
            host.repaintCanvas();
        });
        chLayerFront.setDot(Theme.BLUE);
        chLayerFront.setOnTint(Theme.BLUE);
        chLayerFront.setMonoFont();
        chLayerFront.setToolTipText("Bấm = ẩn/hiện lớp TRƯỚC trên khung xem");
        chLayerFront.addActionListener(e -> {
            if (frontImgs.isEmpty()) return;
            chLayerFront.setOn(!chLayerFront.isOn());
            hideFront = !chLayerFront.isOn();
            updateLayerChips();
            host.repaintCanvas();
        });

        // Danh sách aura tự làm mới ngay trước khi bung combo → không cần nút ⟳ riêng.
        javax.swing.event.PopupMenuListener refresh = new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                refreshAuraList();
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        };
        cbBack.addPopupMenuListener(refresh);
        cbFront.addPopupMenuListener(refresh);

        cbBack.addActionListener(e -> {
            if (suspend) return;
            String n = (String) cbBack.getSelectedItem();
            if (n != null && texDir != null) loadBack(texDir.resolve(n));
        });
        cbFront.addActionListener(e -> {
            if (suspend) return;
            String n = (String) cbFront.getSelectedItem();
            if (n != null && texDir != null) loadFront(texDir.resolve(n));
        });

        tgTwo.addActionListener(e -> {
            twoLayer = tgTwo.isSelected();
            updateFrontEnabled();
            updateLayerChips();
            host.repaintCanvas();
        });

        slScale.addChangeListener(e -> {
            if (suspend) return;
            setScaleValue(slScale.getValue() / 100.0);
        });
        spScale.addChangeListener(e -> {
            if (suspend) return;
            setScaleValue(((Number) spScale.getValue()).doubleValue());
        });
        slX.addChangeListener(e -> { if (!suspend) setOffset(slX.getValue(), null); });
        spX.addChangeListener(e -> { if (!suspend) setOffset(Theme.spinInt(spX), null); });
        slY.addChangeListener(e -> { if (!suspend) setOffset(null, slY.getValue()); });
        spY.addChangeListener(e -> { if (!suspend) setOffset(null, Theme.spinInt(spY)); });
    }

    // ── phạm vi SM / ĐẶC BIỆT ──
    private void setMode(String m) {
        mode = m;
        boolean db = "db".equals(m);
        if (db) { twoLayer = true; tgTwo.setSelected(true); updateFrontEnabled(); }
        segScope.select(db ? 1 : 0);
        btImport.setText(db ? "Import ĐB…" : "Import SM…");   // 3 nút bằng nhau → nhãn phải ngắn
        btCreate.setText(db ? "Tạo / cập nhật cho ĐẶC BIỆT" : "Tạo / cập nhật cho SM");
        chScope.setText("đang sửa: " + (db ? "ĐẶC BIỆT" : "SM"));
        lbAdvEnum.setText(db ? "Enum_HaoQuangDacBiet" : "Enum_HaoQuang");
        lbAdvAnim.setText(db ? "PlayHaoQuangDacBietAnim" : "PlayHaoQuangAnim");
        lbAdvPrefab.setText(db ? "HaoQuangDacBiet2.prefab" : "HaoQuang2.prefab");
        lbNote.setText("ghi vào " + lbAdvEnum.getText() + " · " + lbAdvAnim.getText()
                + " · " + (db ? "HaoQuangDacBiet2" : "HaoQuang2"));
        refreshAuraList();
        revalidate();
        repaint();
    }

    // ── đối tượng canh chỉnh ──
    private void setTarget(int t) {
        target = t;
        segTarget.select(t);
        if (t != T_BOTH) adjustLayer = (t == T_FRONT) ? "truoc" : "sau";
        boolean per = (t != T_BOTH);
        enableRow(rowX, per);
        enableRow(rowY, per);
        if (btApplyPos != null) btApplyPos.setEnabled(per);
        String tip = per ? null : "Vị trí chỉnh riêng từng lớp — chọn Lớp SAU hoặc Lớp TRƯỚC";
        if (rowX != null) rowX.setToolTipText(tip);
        if (rowY != null) rowY.setToolTipText(tip);
        if (btApplyPos != null) btApplyPos.setToolTipText(tip);
        syncAdjust();
    }

    private static void enableRow(JPanel row, boolean on) {
        if (row == null) return;
        for (Component c : row.getComponents()) {
            c.setEnabled(on);
            if (c instanceof Container ct) for (Component k : ct.getComponents()) k.setEnabled(on);
        }
    }

    private String layerKey() { return (target == T_FRONT) ? "truoc" : "sau"; }

    /** Đổ giá trị của đối tượng đang chỉnh vào slider + ô số + nhãn PPU. */
    private void syncAdjust() {
        boolean old = suspend;
        suspend = true;
        double sc = switch (target) {
            case T_FRONT -> frontScale;
            case T_BOTH -> scaleBoth;
            default -> backScale;
        };
        slScale.setValue((int) Math.round(sc * 100));
        spScale.setValue(Math.round(sc * 100) / 100.0);
        double ox = (target == T_FRONT) ? fdx : dx;
        double oy = (target == T_FRONT) ? fdy : dy;
        slX.setValue((int) Math.round(ox));
        spX.setValue((int) Math.round(ox));
        slY.setValue((int) Math.round(oy));
        spY.setValue((int) Math.round(oy));
        Double ppu = (target == T_FRONT) ? frontPpu : backPpu;
        lbPpu.setText(target == T_BOTH ? "PPU riêng từng lớp"
                : ppu == null ? "PPU —" : "PPU " + HaoQuang.fmt(ppu));
        suspend = old;
    }

    private void setScaleValue(double v) {
        v = Math.max(0.1, Math.min(4.0, v));
        switch (target) {
            case T_FRONT -> frontScale = v;
            case T_BOTH -> scaleBoth = v;
            default -> backScale = v;
        }
        syncAdjust();
        host.repaintCanvas();
    }

    private void setOffset(Integer x, Integer y) {
        if (target == T_BOTH) return;
        if (target == T_FRONT) {
            if (x != null) fdx = x;
            if (y != null) fdy = y;
        } else {
            if (x != null) dx = x;
            if (y != null) dy = y;
        }
        syncAdjust();
        host.repaintCanvas();
    }

    private void applyScaleCurrent() {
        if (target == T_BOTH) applyScaleBoth();
        else applyScaleLayer(layerKey());
    }

    private void resetCurrent() {
        if (target == T_BOTH) { scaleBoth = 1; syncAdjust(); host.repaintCanvas(); }
        else resetLayer(layerKey());
    }

    private void updateFrontEnabled() {
        boolean on = tgTwo.isSelected();
        cbFront.setEnabled(on);
        if (btFrontPick != null) btFrontPick.setEnabled(on);
        if (lbFrontName != null) lbFrontName.setForeground(on ? Theme.TEXT_2 : Theme.TEXT_MUTED);
        segTarget.setSegEnabled(T_FRONT, on);
        if (!on && target == T_FRONT) setTarget(T_BACK);
    }

    /** 2 chip công tắc lớp ở góc khung xem: tên aura + trạng thái. */
    private void updateLayerChips() {
        String bn = (backFolder != null) ? backFolder.getFileName().toString() : "chưa chọn";
        chLayerBack.setText((hideBack ? "ẩn lớp SAU · " : "hiện lớp SAU · ") + bn);
        chLayerBack.setOn(!hideBack && !backImgs.isEmpty());
        String fn = frontFolder != null ? frontFolder.getFileName().toString() : "tắt";
        chLayerFront.setText(frontImgs.isEmpty() ? "lớp TRƯỚC · tắt"
                : (hideFront ? "ẩn lớp TRƯỚC · " : "hiện lớp TRƯỚC · ") + fn);
        chLayerFront.setOn(!frontImgs.isEmpty() && !hideFront && twoLayer);
        if (chLayerBack.getParent() != null) {
            chLayerBack.getParent().revalidate();
            chLayerBack.getParent().repaint();
        }
    }

    // ── Móc cho CLI chụp ảnh giao diện (lệnh {@code player hq…}) ──
    /** Chọn hệ ("sm" / "db") như bấm nút. */
    public void debugMode(String m) { setMode(m); }

    /** Nạp aura theo TÊN folder trong Textures/GamePlay/HaoQuang. */
    public void debugLoad(String backName, String frontName) {
        Path t = resolveTexDir();
        if (t == null) return;
        refreshAuraList();
        if (backName != null && !backName.isEmpty()) {
            suspend = true; cbBack.setSelectedItem(backName); suspend = false;
            loadBack(t.resolve(backName));
        }
        if (frontName != null && !frontName.isEmpty()) {
            suspend = true; cbFront.setSelectedItem(frontName); suspend = false;
            loadFront(t.resolve(frontName));
        }
    }

    /** Gọi khi tab được chọn / bỏ chọn — quyết định canvas có vẽ overlay aura không. */
    public void setTabActive(boolean on) {
        active = on && !backImgs.isEmpty();
        if (!on) stopPreview();
        host.repaintCanvas();
    }

    // ═══════════════════════ NẠP FRAME ═══════════════════════
    private Path resolveTexDir() {
        if (texDir != null) return texDir;
        Path f = host.currentSpineFolder();
        if (f != null) texDir = HaoQuang.findHaoQuangTexturesDir(f);
        return texDir;
    }

    private void refreshAuraList() {
        Path t = resolveTexDir();
        List<String> auras = HaoQuang.listExistingAuras(t);
        suspend = true;
        Object b = cbBack.getSelectedItem(), f = cbFront.getSelectedItem();
        cbBack.removeAllItems();
        cbFront.removeAllItems();
        for (String a : auras) { cbBack.addItem(a); cbFront.addItem(a); }
        if (b != null) cbBack.setSelectedItem(b);
        if (f != null) cbFront.setSelectedItem(f);
        suspend = false;
    }

    private void pickBackFolder() {
        Path d = chooseDir("Thư mục ảnh LỚP SAU (slot 0)");
        if (d != null) loadBack(d);
    }

    private void pickFrontFolder() {
        Path d = chooseDir("Thư mục ảnh LỚP TRƯỚC (slot 1)");
        if (d != null) loadFront(d);
    }

    private Path chooseDir(String title) {
        Path init = resolveTexDir();
        JFileChooser fc = new JFileChooser(init != null ? init.toFile() : null);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(title);
        return fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION
                ? fc.getSelectedFile().toPath() : null;
    }

    private void loadBack(Path folder) {
        stopPreview();
        try {
            backFrames = HaoQuang.collectFrameGuids(folder);
            backImgs.clear();
            for (HaoQuang.Frame fr : backFrames) backImgs.add(ImageIO.read(folder.resolve(fr.name()).toFile()));
        } catch (Exception ex) {
            error("Lỗi đọc lớp sau", ex);
            lbBackInfo.setText("Lỗi: " + ex.getMessage());
            lbBackInfo.setForeground(Theme.RED);
            return;
        }
        backFolder = folder;
        frameIdx = 0;
        String base = folder.getFileName().toString();
        txClip.setText("ani" + cap(base));
        txTrigger.setText(base.toLowerCase());
        txEnum.setText("HAO_QUANG_" + base.toUpperCase());
        autofillPaths(folder);
        readBackMeta();
        lbBackInfo.setText(backFrames.size() + " frame · " + backFrames.get(0).name() + " … "
                + backFrames.get(backFrames.size() - 1).name());
        active = true;
        step1.setDone(true);
        step1.setSummary(("db".equals(mode) ? "Import ĐB · " : "Import SM · ") + backFrames.size() + " frame");
        if (step1.isOpen()) step1.setOpen(false);
        syncAdjust();
        updateLayerChips();
        host.repaintCanvas();
    }

    private void loadFront(Path folder) {
        try {
            frontFrames = HaoQuang.collectFrameGuids(folder);
            frontImgs.clear();
            for (HaoQuang.Frame fr : frontFrames) frontImgs.add(ImageIO.read(folder.resolve(fr.name()).toFile()));
        } catch (Exception ex) {
            error("Lỗi đọc lớp trước", ex);
            lbFrontInfo.setText("Lỗi: " + ex.getMessage());
            lbFrontInfo.setForeground(Theme.RED);
            return;
        }
        frontFolder = folder;
        String base = folder.getFileName().toString();
        txFrontClip.setText("ani" + cap(base));
        txFrontTrigger.setText(base.toLowerCase());
        readFrontMeta();
        twoLayer = true;
        tgTwo.setSelected(true);
        updateFrontEnabled();
        lbFrontInfo.setText(frontFrames.size() + " frame · " + base);
        lbFrontInfo.setFont(Theme.mono(12, Font.PLAIN));
        lbFrontInfo.setForeground(Theme.GREEN);
        syncAdjust();
        updateLayerChips();
        host.repaintCanvas();
    }

    /** Đọc PPU + pivot + spriteMode của frame đầu lớp SAU. */
    private void readBackMeta() {
        backPpu = null;
        if (backFrames.isEmpty()) return;
        Path meta = backFolder.resolve(backFrames.get(0).name() + ".meta");
        if (!Files.isRegularFile(meta)) { lbBackInfo.setForeground(Theme.TEXT_MUTED); syncAdjust(); return; }
        String txt = HaoQuang.read(meta);
        backPpu = HaoQuang.readSpritePpu(txt);
        double[] piv = HaoQuang.readSpritePivot(txt);
        if (piv != null) backPivot = piv;
        Integer m = HaoQuang.readSpriteMode(txt);
        if (m != null && m != 1) {
            lbBackInfo.setText(lbBackInfo.getText() + "  ⚠ đang Multiple → bấm \"Sửa import\"");
            lbBackInfo.setForeground(Theme.RED);
        } else {
            lbBackInfo.setForeground(Theme.GREEN);
        }
        syncAdjust();
    }

    private void readFrontMeta() {
        frontPpu = null;
        if (frontFrames.isEmpty()) return;
        Path meta = frontFolder.resolve(frontFrames.get(0).name() + ".meta");
        if (Files.isRegularFile(meta)) {
            String txt = HaoQuang.read(meta);
            frontPpu = HaoQuang.readSpritePpu(txt);
            double[] piv = HaoQuang.readSpritePivot(txt);
            if (piv != null) frontPivot = piv;
        }
        syncAdjust();
    }

    private void autofillPaths(Path folder) {
        Map<String, Path> p = HaoQuang.detectClientPaths(folder);
        setIfEmpty(txOutDir, p.get("anim_out_dir"));
        setIfEmpty(txController, p.get("controller"));
        setIfEmpty(txEnumFile, p.get("enum"));
        setIfEmpty(txEnumDbFile, p.get("enum_dacbiet"));
        setIfEmpty(txActor, p.get("actorvisual"));
        setIfEmpty(txPrefab, p.get("prefab"));
    }

    private static void setIfEmpty(JTextField f, Path p) {
        if (p != null && f.getText().trim().isEmpty()) f.setText(p.toString());
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ═══════════════════════ XEM THỬ ═══════════════════════
    private void togglePreview() {
        if (playing) stopPreview(); else startPreview();
    }

    private void startPreview() {
        if (backImgs.isEmpty()) { info("Chọn / nạp một hào quang trước."); return; }
        playing = true;
        btPlay.setText("Dừng");
        btPlay.setIcon(Theme.icon(Theme.IC_PAUSE, 11, Theme.GREEN));
        previewTimer.setDelay(Math.max(16, 1000 / Math.max(1, Theme.spinInt(spFps))));
        previewTimer.start();
    }

    private void stopPreview() {
        playing = false;
        btPlay.setText("Chạy");
        btPlay.setIcon(Theme.icon(Theme.IC_PLAY, 11, Theme.GREEN));
        previewTimer.stop();
    }

    private void tickPreview() {
        if (backImgs.isEmpty()) { stopPreview(); return; }
        frameIdx++;
        if (frameIdx >= backImgs.size()) {
            if (chkLoop.isSelected()) frameIdx = 0;
            else { frameIdx = backImgs.size() - 1; stopPreview(); }
        }
        host.repaintCanvas();
    }

    // ═══════════════════════ CANVAS ĐỌC ═══════════════════════
    public BufferedImage backImage() {
        return backImgs.isEmpty() ? null : backImgs.get(Math.min(frameIdx, backImgs.size() - 1));
    }

    public BufferedImage frontImage() {
        return frontImgs.isEmpty() ? null : frontImgs.get(frameIdx % frontImgs.size());
    }

    public boolean hasFrames() { return !backImgs.isEmpty(); }

    /** Kéo chuột trên khung xem: cộng vào offset của LỚP ĐANG CHỌN (đơn vị = skeleton unit). */
    public void dragBy(double sdx, double sdy) {
        if (adjustLayer.equals("truoc")) {
            fdx = Math.round((fdx + sdx) * 10) / 10.0;
            fdy = Math.round((fdy + sdy) * 10) / 10.0;
        } else {
            dx = Math.round((dx + sdx) * 10) / 10.0;
            dy = Math.round((dy + sdy) * 10) / 10.0;
        }
        syncAdjust();
    }

    // ═══════════════════════ ÁP VÀO GAME ═══════════════════════
    /** Danh sách .meta của một lớp (ném lỗi nếu thiếu file). */
    private List<Path> metasOf(Path folder, List<HaoQuang.Frame> frames) {
        List<Path> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (HaoQuang.Frame f : frames) {
            Path m = folder.resolve(f.name() + ".meta");
            if (!Files.isRegularFile(m)) missing.add(m.getFileName().toString());
            out.add(m);
        }
        if (!missing.isEmpty()) throw new HaoQuang.HqError("Thiếu .meta: " + String.join(", ", missing));
        return out;
    }

    private boolean isBack(String layer) { return layer.equals("sau"); }

    /** Áp thanh Scale của 1 lớp thành PPU rồi đưa Scale về 1 (xem trước không nhảy). */
    private void applyScaleLayer(String layer) {
        boolean b = isBack(layer);
        Path folder = b ? backFolder : frontFolder;
        List<HaoQuang.Frame> frames = b ? backFrames : frontFrames;
        Double ppu = b ? backPpu : frontPpu;
        double factor = b ? backScale : frontScale;
        if (folder == null || frames.isEmpty() || ppu == null) {
            error2("Chưa sẵn sàng", "Lớp " + layer.toUpperCase() + ": cần nạp aura đọc được PPU trước.");
            return;
        }
        if (Math.abs(factor - 1.0) <= 1e-4) { info("Thanh Scale đang = 1 → không có gì để ghi."); return; }
        double newPpu = Math.round(ppu / factor * 10000.0) / 10000.0;
        List<Path> metas;
        try { metas = metasOf(folder, frames); } catch (HaoQuang.HqError ex) { error2("Thiếu .meta", ex.getMessage()); return; }
        if (!confirm("Áp scale vào game?", "Lớp " + layer.toUpperCase() + " (" + folder.getFileName() + "): PPU "
                + HaoQuang.fmt(ppu) + " → " + HaoQuang.fmt(newPpu) + String.format(" (×%.3f).%n", factor)
                + "Ghi " + metas.size() + " frame .meta rồi đưa Scale về 1. Tiếp tục?")) return;
        for (Path m : metas) HaoQuang.write(m, HaoQuang.setSpritePpu(HaoQuang.read(m), newPpu));
        if (b) { backPpu = newPpu; backScale = 1; } else { frontPpu = newPpu; frontScale = 1; }
        syncAdjust();
        saveCalib();
        host.repaintCanvas();
        info("Lớp " + layer.toUpperCase() + ": PPU mới = " + HaoQuang.fmt(newPpu)
                + "\n\n→ Unity refresh (Ctrl+R) là đúng cỡ trong game.");
    }

    /** Áp thanh "Scale CẢ 2 lớp": chia PPU cả 2 lớp cho cùng hệ số. */
    private void applyScaleBoth() {
        double factor = scaleBoth;
        if (Math.abs(factor - 1.0) <= 1e-4) { info("Thanh Scale cả 2 đang = 1 → không có gì để ghi."); return; }
        record Layer(String label, Path folder, List<HaoQuang.Frame> frames, double ppu, boolean back) { }
        List<Layer> ls = new ArrayList<>();
        if (!backFrames.isEmpty() && backPpu != null) ls.add(new Layer("SAU", backFolder, backFrames, backPpu, true));
        if (!frontFrames.isEmpty() && frontPpu != null) ls.add(new Layer("TRƯỚC", frontFolder, frontFrames, frontPpu, false));
        if (ls.isEmpty()) { error2("Chưa có gì để áp", "Cần nạp aura đọc được PPU trước."); return; }
        StringBuilder msg = new StringBuilder();
        List<List<Path>> plans = new ArrayList<>();
        for (Layer l : ls) {
            List<Path> metas;
            try { metas = metasOf(l.folder(), l.frames()); }
            catch (HaoQuang.HqError ex) { error2("Thiếu .meta", "Lớp " + l.label() + ": " + ex.getMessage()); return; }
            plans.add(metas);
            msg.append("Lớp ").append(l.label()).append(" (").append(l.folder().getFileName()).append("): PPU ")
               .append(HaoQuang.fmt(l.ppu())).append(" → ")
               .append(HaoQuang.fmt(Math.round(l.ppu() / factor * 10000.0) / 10000.0)).append('\n');
        }
        if (!confirm("Áp scale CẢ 2 lớp vào game?", msg + String.format("%n(×%.3f kích thước) Tiếp tục?", factor))) return;
        for (int i = 0; i < ls.size(); i++) {
            Layer l = ls.get(i);
            double np = Math.round(l.ppu() / factor * 10000.0) / 10000.0;
            for (Path m : plans.get(i)) HaoQuang.write(m, HaoQuang.setSpritePpu(HaoQuang.read(m), np));
            if (l.back()) backPpu = np; else frontPpu = np;
        }
        scaleBoth = 1;
        syncAdjust();
        saveCalib();
        host.repaintCanvas();
        info(msg + "\n→ Unity refresh (Ctrl+R) là đúng cỡ trong game.");
    }

    /**
     * Áp Offset X/Y của 1 lớp thành {@code spritePivot} rồi đưa offset về 0.
     * {@code px −= ox·k·PPU/w} · {@code py += oy·k·PPU/h} với {@code k = importScale × 0.7}
     * (skeleton unit → world unit); {@code PPU/w} đổi world unit thành phần trăm bề rộng sprite.
     */
    private void applyOffsetLayer(String layer) {
        Float imp = host.importScale();
        if (imp == null) {
            error2("Chưa load nhân vật", "Cần Load 1 nhân vật (có SkeletonData.asset) để quy vị trí ra đơn vị game.");
            return;
        }
        double k = imp * PlayerViewerFrame.PLAYER_SPINE_SCALE;
        boolean b = isBack(layer);
        Path folder = b ? backFolder : frontFolder;
        List<HaoQuang.Frame> frames = b ? backFrames : frontFrames;
        Double ppu = b ? backPpu : frontPpu;
        BufferedImage img = b ? (backImgs.isEmpty() ? null : backImgs.get(0))
                              : (frontImgs.isEmpty() ? null : frontImgs.get(0));
        double ox = b ? dx : fdx, oy = b ? dy : fdy;
        double[] piv = b ? backPivot : frontPivot;
        if (folder == null || frames.isEmpty() || ppu == null || img == null) {
            error2("Chưa sẵn sàng", "Lớp " + layer.toUpperCase() + ": cần nạp aura đọc được PPU + frame trước.");
            return;
        }
        if (Math.abs(ox) <= 1e-6 && Math.abs(oy) <= 1e-6) { info("Offset đang = 0 → không có vị trí nào để ghi."); return; }
        double nx = Math.round((piv[0] - ox * k * ppu / img.getWidth()) * 100000.0) / 100000.0;
        double ny = Math.round((piv[1] + oy * k * ppu / img.getHeight()) * 100000.0) / 100000.0;
        List<Path> metas;
        try { metas = metasOf(folder, frames); } catch (HaoQuang.HqError ex) { error2("Thiếu .meta", ex.getMessage()); return; }
        if (!confirm("Áp vị trí vào game?", String.format(
                "Lớp %s (%s): pivot (%.3f, %.3f) → (%.3f, %.3f).%nGhi %d frame .meta rồi đưa Offset về 0. Tiếp tục?",
                layer.toUpperCase(), folder.getFileName(), piv[0], piv[1], nx, ny, metas.size()))) return;
        for (Path m : metas) HaoQuang.write(m, HaoQuang.setSpritePivot(HaoQuang.read(m), nx, ny));
        if (b) { backPivot = new double[]{nx, ny}; dx = 0; dy = 0; }
        else { frontPivot = new double[]{nx, ny}; fdx = 0; fdy = 0; }
        syncAdjust();
        saveCalib();
        host.repaintCanvas();
        info(String.format("Lớp %s: pivot mới = (%.3f, %.3f).%n%n→ Unity refresh (Ctrl+R) là đúng vị trí trong game.",
                layer.toUpperCase(), nx, ny));
    }

    /** Đổi cỡ trong game bằng hệ số PPU trực tiếp (không qua thanh Scale) — bước 4. */
    private void applyGameSize(String layer, double factor) {
        boolean b = isBack(layer);
        Path folder = b ? backFolder : frontFolder;
        List<HaoQuang.Frame> frames = b ? backFrames : frontFrames;
        Double ppu = b ? backPpu : frontPpu;
        if (folder == null || frames.isEmpty()) { error2("Chưa chọn aura", "Nạp một hào quang trước."); return; }
        if (ppu == null) { error2("Thiếu PPU", "Không đọc được PPU của frame."); return; }
        if (factor <= 0) { error2("Sai hệ số", "Hệ số phải > 0."); return; }
        double newPpu = Math.round(ppu / factor * 10000.0) / 10000.0;
        List<Path> metas;
        try { metas = metasOf(folder, frames); } catch (HaoQuang.HqError ex) { error2("Thiếu .meta", ex.getMessage()); return; }
        if (!confirm("Đổi kích thước trong game?", "Aura: " + folder.getFileName() + "\nPPU "
                + HaoQuang.fmt(ppu) + " → " + HaoQuang.fmt(newPpu) + "  (×" + HaoQuang.fmt(factor) + " kích thước)\n"
                + "Ghi vào " + metas.size() + " frame .meta. Tiếp tục?")) return;
        for (Path m : metas) HaoQuang.write(m, HaoQuang.setSpritePpu(HaoQuang.read(m), newPpu));
        if (b) backPpu = newPpu; else frontPpu = newPpu;
        syncAdjust();
        host.repaintCanvas();
        info(folder.getFileName() + ": PPU mới = " + HaoQuang.fmt(newPpu)
                + "\n\n→ Unity refresh (Ctrl+R) là thấy aura đổi cỡ trong game.");
    }

    private void resetLayer(String layer) {
        if (isBack(layer)) { backScale = 1; dx = 0; dy = 0; }
        else { frontScale = 1; fdx = 0; fdy = 0; }
        syncAdjust();
        host.repaintCanvas();
    }

    /** Đưa mọi frame của aura đang chọn về Sprite Single (giữ guid) — sửa lỗi "game không hiện". */
    private void fixImport() {
        if (backFolder == null || backFrames.isEmpty()) { error2("Chưa chọn aura", "Nạp một hào quang trước."); return; }
        HaoQuang.Template tmpl = HaoQuang.templateMetaText(resolveTexDir());
        List<Path> metas;
        try { metas = metasOf(backFolder, backFrames); } catch (HaoQuang.HqError ex) { error2("Thiếu .meta", ex.getMessage()); return; }
        int bad = 0;
        for (Path m : metas) {
            Integer mo = HaoQuang.readSpriteMode(HaoQuang.read(m));
            if (mo == null || mo != 1) bad++;
        }
        if (!confirm("Sửa import về Single?", "Aura: " + backFolder.getFileName() + "  (mẫu: " + tmpl.source() + ")\n"
                + "Đặt " + metas.size() + " frame về Sprite Single, GIỮ guid, pivot theo mẫu.\n"
                + "(" + bad + " frame đang sai chế độ). Tiếp tục?")) return;
        for (Path m : metas) HaoQuang.write(m, HaoQuang.retargetMeta(tmpl.text(), HaoQuang.readMetaGuid(m)));
        loadBack(backFolder);
        info(metas.size() + " frame của '" + backFolder.getFileName() + "' đã về Sprite Single (mẫu: "
                + tmpl.source() + ").\n\n→ Unity refresh (Ctrl+R) → game sẽ hiện hào quang.");
    }

    /** Tạo hào quang mới: copy PNG vào {@code HaoQuang/<tên>/} + sinh .meta Single. */
    private void newAura() {
        Path tex = resolveTexDir();
        if (tex == null) {
            tex = chooseDir("Chọn thư mục …/Textures/GamePlay/HaoQuang");
            if (tex == null) return;
            texDir = tex;
        }
        String name = JOptionPane.showInputDialog(this,
                "Tên folder (chữ / số / gạch dưới, không dấu, không cách):", "Tên hào quang mới",
                JOptionPane.QUESTION_MESSAGE);
        if (name == null) return;
        name = name.trim();
        if (!name.matches("[A-Za-z0-9_]+")) { error2("Tên không hợp lệ", "Chỉ dùng chữ cái, số, gạch dưới (_)."); return; }
        Path dest = tex.resolve(name);
        if (Files.isDirectory(dest) && !HaoQuang.findFramePngs(dest).isEmpty()
                && !confirm("Đã tồn tại", "Folder '" + name + "' đã có ảnh. Thêm frame vào tiếp?")) return;

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Chọn các ảnh frame PNG (theo thứ tự tên)");
        fc.setMultiSelectionEnabled(true);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PNG", "png"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] files = fc.getSelectedFiles();
        if (files == null || files.length == 0) return;

        try {
            HaoQuang.Template tmpl = HaoQuang.templateMetaText(tex);
            Files.createDirectories(dest);
            Path folderMeta = dest.resolveSibling(dest.getFileName() + ".meta");
            if (!Files.exists(folderMeta)) HaoQuang.write(folderMeta, HaoQuang.buildFolderMeta().text());
            List<Path> srcs = new ArrayList<>();
            for (File f : files) srcs.add(f.toPath());
            srcs.sort(HaoQuang.BY_NAME);
            for (Path sp : srcs) {
                Path dst = dest.resolve(sp.getFileName().toString());
                Files.copy(sp, dst, StandardCopyOption.REPLACE_EXISTING);
                HaoQuang.write(dst.resolveSibling(dst.getFileName() + ".meta"),
                        HaoQuang.cloneMeta(tmpl.text()).text());
            }
            refreshAuraList();
            suspend = true; cbBack.setSelectedItem(name); suspend = false;
            loadBack(dest);
            info("Đã tạo: " + dest + "\n" + srcs.size() + " frame + .meta (Sprite Single).\n\n"
                    + "→ Mở Unity refresh (Ctrl+R) để import, rồi Xem thử / căn chỉnh.");
        } catch (Exception ex) {
            error("Lỗi tạo frame", ex);
        }
    }

    // ═══════════════════════ IMPORT LẠI CÁI ĐÃ LÀM ═══════════════════════
    private void importExisting(boolean dacbiet) {
        String enumCs = dacbiet ? "Enum_HaoQuangDacBiet" : "Enum_HaoQuang";
        Path tex = resolveTexDir();
        if (tex == null) {
            error2("Chưa thấy Textures/HaoQuang",
                    "Load 1 thư mục nhân vật trước (ô Thư mục Spine) để tool tự dò đường dẫn client.");
            return;
        }
        Map<String, Path> paths = HaoQuang.detectClientPaths(tex);
        Path epath = paths.get(dacbiet ? "enum_dacbiet" : "enum");
        if (epath == null || !Files.isRegularFile(epath)) {
            error2("Chưa thấy " + enumCs + ".cs", "Tự dò không ra " + enumCs + ".cs từ:\n" + tex);
            return;
        }
        List<HaoQuang.EnumEntry> entries = new ArrayList<>();
        for (HaoQuang.EnumEntry e : HaoQuang.listEnumValues(HaoQuang.read(epath), enumCs)) {
            if (e.value() != 0 && !e.name().equalsIgnoreCase("NONE")) entries.add(e);
        }
        if (entries.isEmpty()) { info("Không thấy hào quang nào trong " + enumCs + "."); return; }

        List<String> folders = HaoQuang.listExistingAuras(tex);
        Map<String, String> byLower = new LinkedHashMap<>();
        for (String f : folders) byLower.put(f.toLowerCase(), f);
        Path apath = paths.get("actorvisual");
        String av = (apath != null && Files.isRegularFile(apath)) ? HaoQuang.read(apath) : "";

        record Item(String name, int value, String back, String front) { }
        List<Item> items = new ArrayList<>();
        for (HaoQuang.EnumEntry e : entries) {
            HaoQuang.TriggerPair tp = HaoQuang.switchCaseTriggers(av, e.name(), enumCs);
            String back = tp.back() == null ? null : byLower.get(tp.back().toLowerCase());
            if (back == null) back = nameFallback(e.name(), byLower);
            String front = tp.front() == null ? null : byLower.get(tp.front().toLowerCase());
            items.add(new Item(e.name(), e.value(), back, front));
        }
        items.sort((a, b) -> {
            int c = Boolean.compare(a.back() == null, b.back() == null);
            return c != 0 ? c : a.name().compareToIgnoreCase(b.name());
        });

        DefaultListModel<String> mdl = new DefaultListModel<>();
        for (Item it : items) {
            String tag = it.back() == null ? "   ⚠ chưa thấy folder frame"
                    : (it.front() != null ? "   sau=" + it.back() + " + trước=" + it.front()
                                          : "   sau=" + it.back());
            mdl.addElement(it.name() + " = " + it.value() + tag);
        }
        JList<String> list = new JList<>(mdl);
        list.setSelectedIndex(0);
        list.setFont(Theme.font(12, Font.PLAIN));
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(520, 320));
        JPanel wrap = new JPanel(new BorderLayout(0, 6));
        wrap.add(new JLabel("<html>Chọn hào quang " + (dacbiet ? "ĐẶC BIỆT" : "SM")
                + " → nạp ĐỦ lớp sau + lớp trước để chỉnh.</html>"), BorderLayout.NORTH);
        wrap.add(sp, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, wrap, "Import hào quang đã làm",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        int i = list.getSelectedIndex();
        if (i < 0) return;
        Item it = items.get(i);
        if (it.back() == null) {
            error2("Chưa có folder frame", "'" + it.name() + "' chưa dò ra folder frame trong Textures.\n"
                    + "Đặt tên folder cho khớp, hoặc chọn thủ công ở mục Lớp SAU.");
            return;
        }
        refreshAuraList();
        suspend = true; cbBack.setSelectedItem(it.back()); suspend = false;
        loadBack(tex.resolve(it.back()));
        txEnum.setText(it.name());              // GIỮ đúng enum đã có để xuất lại dùng lại
        if (it.front() != null) {
            suspend = true; cbFront.setSelectedItem(it.front()); suspend = false;
            loadFront(tex.resolve(it.front()));
        } else if (dacbiet) {
            twoLayer = true;
            tgTwo.setSelected(true);
            updateFrontEnabled();
        }
    }

    /** Dò folder theo tên enum khi ActorVisual không cho biết trigger. */
    private static String nameFallback(String name, Map<String, String> byLower) {
        List<String> cands = new ArrayList<>();
        String up = name.toUpperCase();
        if (up.startsWith("HAO_QUANG_DB_")) cands.add(name.substring("HAO_QUANG_DB_".length()).toLowerCase());
        if (up.startsWith("HAO_QUANG_")) cands.add(name.substring("HAO_QUANG_".length()).toLowerCase());
        cands.add(name.toLowerCase());
        cands.add(name.toLowerCase().replace("hao_quang_", ""));
        for (String c : cands) if (byLower.containsKey(c)) return byLower.get(c);
        return null;
    }

    // ═══════════════════════ XUẤT / CẬP NHẬT ═══════════════════════
    private void doExport(boolean dacbiet) {
        stopPreview();
        if (backFrames.isEmpty()) { error2("Chưa có frame", "Nạp một hào quang trước."); return; }
        String clip = txClip.getText().trim(), trigger = txTrigger.getText().trim();
        if (clip.isEmpty() || trigger.isEmpty()) { error2("Thiếu thông số", "Cần tên clip và trigger."); return; }
        Path outDir = pathOf(txOutDir);
        if (outDir == null || !Files.isDirectory(outDir)) { error2("Thiếu output", "Chọn thư mục xuất .anim hợp lệ."); return; }
        int fps = Math.max(1, Theme.spinInt(spFps));
        List<String> guids = new ArrayList<>();
        for (HaoQuang.Frame f : backFrames) guids.add(f.guid());

        try {
            Path cpath = pathOf(txController);
            // Cập nhật aura CŨ: trigger đã có trong controller, 1 lớp, hệ SM ⇒ chỉ ghi lại .anim
            if (cpath != null && Files.isRegularFile(cpath) && !twoLayer && !dacbiet
                    && HaoQuang.controllerHasTrigger(HaoQuang.read(cpath), trigger)) {
                updateExisting(cpath, trigger, guids, fps, outDir);
                return;
            }
            if (twoLayer || dacbiet) { exportTwoLayer(outDir, fps, guids, dacbiet); return; }
            exportOneLayer(outDir, fps, guids, clip, trigger);
        } catch (HaoQuang.HqError ex) {
            error2("Lỗi tạo hào quang", ex.getMessage());
        } catch (Exception ex) {
            error("Lỗi tạo hào quang", ex);
        }
    }

    /** Aura cũ: chỉ ghi lại .anim trỏ frame mới, GIỮ .meta ⇒ controller vẫn link đúng. */
    private void updateExisting(Path cpath, String trigger, List<String> guids, int fps, Path outDir) {
        String clipGuid = HaoQuang.controllerTriggerClipGuid(HaoQuang.read(cpath), trigger);
        if (clipGuid == null) {
            error2("Không tìm thấy clip", "Trigger '" + trigger + "' có trong controller nhưng không lần ra state/clip.");
            return;
        }
        Path clipPath = HaoQuang.findAnimByGuid(outDir, clipGuid);
        if (clipPath == null) {
            error2("Không thấy file .anim", "Không tìm thấy .anim guid " + clipGuid + " trong " + outDir);
            return;
        }
        String stem = clipPath.getFileName().toString().replaceFirst("\\.anim$", "");
        if (!confirm("Cập nhật aura cũ?", "'" + trigger + "' đã có sẵn trong game.\n\nGhi lại " + clipPath.getFileName()
                + " → trỏ tới " + guids.size() + " frame mới (" + fps + " fps), GIỮ controller/enum/code.\n\nTiếp tục?")) return;
        HaoQuang.write(clipPath, HaoQuang.buildAnimClipYaml(stem, guids, 1.0 / fps, 60, chkLoop.isSelected()));
        info("Đã ghi lại " + clipPath.getFileName() + " trỏ tới " + guids.size() + " frame mới.\n"
                + "(guid clip giữ nguyên nên controller vẫn link đúng)\n\n→ Unity refresh (Ctrl+R).");
    }

    private void exportOneLayer(Path outDir, int fps, List<String> guids, String clip, String trigger) {
        Path animPath = outDir.resolve(clip + ".anim");
        if (Files.exists(animPath) && !confirm("Ghi đè?", animPath.getFileName() + " đã tồn tại. Ghi đè?")) return;
        String enumName = txEnum.getText().trim();
        String clipGuid = HaoQuang.newGuid();
        String animText = HaoQuang.buildAnimClipYaml(clip, guids, 1.0 / fps, 60, chkLoop.isSelected());
        String metaText = HaoQuang.buildAnimMetaYaml(clipGuid);

        List<Path> paths = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        summary.add("• Clip: " + animPath);
        summary.add("  " + guids.size() + " frame, " + fps + " fps");

        if (chkDoController.isSelected()) {
            Path cpath = mustFile(txController, "HaoQuang.controller");
            paths.add(cpath);
            texts.add(HaoQuang.addParamStateTransition(HaoQuang.read(cpath), trigger, clip, clipGuid));
            summary.add("• Controller: +trigger '" + trigger + "' +state '" + clip + "'");
        }
        if (chkDoCode.isSelected()) {
            if (enumName.isEmpty()) throw new HaoQuang.HqError("Cần tên Enum khi cập nhật code.");
            Path epath = mustFile(txEnumFile, "Enum_HaoQuang.cs");
            Path apath = mustFile(txActor, "ActorVisual.cs");
            String e0 = HaoQuang.read(epath);
            Integer exist = HaoQuang.enumValueOf(e0, enumName);
            if (exist == null) {
                HaoQuang.EnumAdd ea = HaoQuang.addEnumValue(e0, enumName, "Enum_HaoQuang");
                paths.add(epath); texts.add(ea.text());
                summary.add("• Enum: " + enumName + " = " + ea.value() + " (mới)");
            } else {
                summary.add("• Enum: " + enumName + " = " + exist + " (dùng lại)");
            }
            String a0 = HaoQuang.read(apath);
            if (HaoQuang.switchHasCase(a0, enumName, "Enum_HaoQuang")) {
                summary.add("• ActorVisual: case " + enumName + " đã có (giữ nguyên)");
            } else {
                paths.add(apath); texts.add(HaoQuang.addSwitchCase(a0, enumName, trigger));
                summary.add("• ActorVisual: +case " + enumName + " → SetTrigger(\"" + trigger + "\")");
            }
        }
        HaoQuang.write(animPath, animText);
        HaoQuang.write(animPath.resolveSibling(animPath.getFileName() + ".meta"), metaText);
        summary.addAll(commit(paths, texts));
        info(String.join("\n", summary) + "\n\n→ Mở Unity re-import (Ctrl+R) là dùng được.");
    }

    private void exportTwoLayer(Path outDir, int fps, List<String> backGuids, boolean dacbiet) {
        if (frontFrames.isEmpty()) { error2("Thiếu lớp trước", "Đọc ảnh LỚP TRƯỚC trước đã."); return; }
        String cb = txClip.getText().trim(), tb = txTrigger.getText().trim();
        String cf = txFrontClip.getText().trim(), tf = txFrontTrigger.getText().trim();
        String en = txEnum.getText().trim();
        if (cb.isEmpty() || tb.isEmpty() || cf.isEmpty() || tf.isEmpty() || en.isEmpty()) {
            error2("Thiếu thông số", "Cần đủ tên clip/trigger (sau + trước) và Enum.");
            return;
        }
        if (tb.equals(tf)) { error2("Trùng trigger", "Trigger lớp SAU và lớp TRƯỚC phải khác nhau."); return; }
        List<String> frontGuids = new ArrayList<>();
        for (HaoQuang.Frame f : frontFrames) frontGuids.add(f.guid());
        boolean loop = chkLoop.isSelected();

        String enumCs = dacbiet ? "Enum_HaoQuangDacBiet" : "Enum_HaoQuang";
        String method = dacbiet ? "PlayHaoQuangDacBietAnim" : "PlayHaoQuangAnim";
        String animVar = dacbiet ? "_animHaoQuangDacBiet" : "_animHaoQuang";
        String frontGo = dacbiet ? "HaoQuangDacBiet2" : "HaoQuang2";

        Path cpath = mustFile(txController, "HaoQuang.controller");
        Path epath = mustFile(dacbiet ? txEnumDbFile : txEnumFile, enumCs + ".cs");
        Path apath = mustFile(txActor, "ActorVisual.cs");
        Path ppath = pathOf(txPrefab);

        String c0 = HaoQuang.read(cpath), ctext = c0;
        boolean backExists = HaoQuang.controllerHasTrigger(c0, tb);
        boolean frontExists = HaoQuang.controllerHasTrigger(c0, tf);
        if (!confirm("Tạo hào quang 2 lớp?",
                "Lớp SAU — " + (backExists ? "dùng lại '" + tb + "'" : "TẠO MỚI '" + tb + "' (" + backGuids.size() + " frame)") + "\n"
                + "Lớp TRƯỚC — " + (frontExists ? "dùng lại '" + tf + "'" : "TẠO MỚI '" + tf + "' (" + frontGuids.size() + " frame)") + "\n"
                + "Enum: " + en + "\n+ case bật cả 2 slot, + nâng " + frontGo + " ra trước.\n\nTiếp tục?")) return;

        List<Path> newPaths = new ArrayList<>();
        List<String> newTexts = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        if (backExists) summary.add("• Lớp SAU: dùng lại '" + tb + "'");
        else {
            String g = HaoQuang.newGuid();
            Path a = outDir.resolve(cb + ".anim");
            newPaths.add(a); newTexts.add(HaoQuang.buildAnimClipYaml(cb, backGuids, 1.0 / fps, 60, loop));
            newPaths.add(a.resolveSibling(a.getFileName() + ".meta")); newTexts.add(HaoQuang.buildAnimMetaYaml(g));
            ctext = HaoQuang.addParamStateTransition(ctext, tb, cb, g);
            summary.add("• Lớp SAU mới: " + a.getFileName() + " (trigger " + tb + ")");
        }
        if (frontExists) summary.add("• Lớp TRƯỚC: dùng lại '" + tf + "'");
        else {
            String g = HaoQuang.newGuid();
            Path a = outDir.resolve(cf + ".anim");
            newPaths.add(a); newTexts.add(HaoQuang.buildAnimClipYaml(cf, frontGuids, 1.0 / fps, 60, loop));
            newPaths.add(a.resolveSibling(a.getFileName() + ".meta")); newTexts.add(HaoQuang.buildAnimMetaYaml(g));
            ctext = HaoQuang.addParamStateTransition(ctext, tf, cf, g);
            summary.add("• Lớp TRƯỚC mới: " + a.getFileName() + " (trigger " + tf + ")");
        }

        List<Path> editPaths = new ArrayList<>();
        List<String> editTexts = new ArrayList<>();
        if (!ctext.equals(c0)) { editPaths.add(cpath); editTexts.add(ctext); }

        String e0 = HaoQuang.read(epath);
        Integer exist = HaoQuang.enumValueOf(e0, en);
        if (exist == null) {
            HaoQuang.EnumAdd ea = HaoQuang.addEnumValue(e0, en, enumCs);
            editPaths.add(epath); editTexts.add(ea.text());
            summary.add("• " + enumCs + ": " + en + " = " + ea.value() + " (mới)");
        } else {
            summary.add("• Enum " + en + " = " + exist + " (dùng lại)");
        }

        String a0 = HaoQuang.read(apath);
        HaoQuang.Upsert up = HaoQuang.upsertSwitchCaseDual(a0, en, tb, tf, method, animVar, enumCs);
        if (!up.text().equals(a0)) {
            editPaths.add(apath); editTexts.add(up.text());
            summary.add("• ActorVisual: case " + en
                    + ("replaced".equals(up.how()) ? " nâng thành 2 lớp" : " thêm mới (2 slot)")
                    + " (" + tb + "+" + tf + ")");
        } else {
            summary.add("• ActorVisual: case " + en + " đã đúng 2 lớp");
        }

        if (ppath != null && Files.isRegularFile(ppath)) {
            HaoQuang.Sorting so = HaoQuang.setGameObjectSpriteSorting(HaoQuang.read(ppath), frontGo, 1);
            if (so.oldOrder() != null && so.oldOrder() != 1) {
                editPaths.add(ppath); editTexts.add(so.text());
                summary.add("• Prefab: " + frontGo + " sortingOrder " + so.oldOrder() + "→1 (ra trước)");
            } else if (so.oldOrder() != null) {
                summary.add("• Prefab: " + frontGo + " đã ở trước (sortingOrder 1)");
            } else {
                summary.add("⚠ Prefab: chưa thấy GameObject '" + frontGo + "' → tự thêm trong Unity.");
            }
        } else {
            summary.add("⚠ Không có Player.prefab → tự chỉnh " + frontGo + " ra trước trong Unity.");
        }

        for (int i = 0; i < newPaths.size(); i++) HaoQuang.write(newPaths.get(i), newTexts.get(i));
        summary.addAll(commit(editPaths, editTexts));
        info(String.join("\n", summary) + "\n\n→ Unity refresh (Ctrl+R) là dùng được.");
    }

    /** Ghi các file BỊ SỬA kèm backup ra ngoài Assets. Trả về dòng tóm tắt backup. */
    private List<String> commit(List<Path> paths, List<String> texts) {
        List<String> baks = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            baks.add(backup(paths.get(i)).getFileName().toString());
            HaoQuang.write(paths.get(i), texts.get(i));
        }
        return baks.isEmpty() ? List.of() : List.of("", "Backup: " + String.join(", ", baks));
    }

    /** Backup ra thư mục riêng NGOÀI {@code Assets/} — để trong đó Unity import .bak thành rác. */
    private Path backup(Path p) {
        try {
            Path dir = toolDir().resolve(".haoquang_backups");
            Files.createDirectories(dir);
            Path bak = dir.resolve(p.getFileName() + ".bak");
            for (int i = 1; Files.exists(bak); i++) bak = dir.resolve(p.getFileName() + ".bak" + i);
            Files.copy(p, bak, StandardCopyOption.COPY_ATTRIBUTES);
            return bak;
        } catch (Exception e) {
            throw new HaoQuang.HqError("Không backup được " + p + ": " + e.getMessage());
        }
    }

    private Path mustFile(JTextField f, String label) {
        Path p = pathOf(f);
        if (p == null || !Files.isRegularFile(p)) throw new HaoQuang.HqError("Không thấy " + label + ": " + p);
        return p;
    }

    private static Path pathOf(JTextField f) {
        String s = f.getText().trim();
        return s.isEmpty() ? null : Paths.get(s);
    }

    // ═══════════════════════ LƯU CANH CHỈNH ═══════════════════════
    private static Path toolDir() {
        try {
            Path p = Paths.get(HaoQuangPanel.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path d = Files.isDirectory(p) ? p : p.getParent();
            if (d != null && "classes".equals(String.valueOf(d.getFileName()))) d = d.getParent();
            if (d != null && "target".equals(String.valueOf(d.getFileName()))) d = d.getParent();
            return d != null ? d : Paths.get(".").toAbsolutePath();
        } catch (Exception e) {
            return Paths.get(".").toAbsolutePath();
        }
    }

    private Path calibFile() { return toolDir().resolve(".haoquang_calib.json"); }

    private void loadCalib() {
        try {
            String s = HaoQuang.read(calibFile());
            scaleBoth = num(s, "scale", 1);
            dx = num(s, "dx", 0);
            dy = num(s, "dy", 0);
            fdx = num(s, "fdx", 0);
            fdy = num(s, "fdy", 0);
            frontScale = num(s, "fscale", 1);
            backScale = num(s, "bscale", 1);
            behind = !s.contains("\"behind\": false");
            chBehind.setOn(behind);
            syncAdjust();
            updateLayerChips();
        } catch (Exception ignored) { }
    }

    private static double num(String json, String key, double def) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : def;
    }

    private void saveCalib() {
        try {
            String json = String.format(java.util.Locale.ROOT,
                    "{%n  \"scale\": %.4f,%n  \"dx\": %.1f,%n  \"dy\": %.1f,%n  \"behind\": %s,"
                    + "%n  \"fdx\": %.1f,%n  \"fdy\": %.1f,%n  \"fscale\": %.4f,%n  \"bscale\": %.4f%n}%n",
                    scaleBoth, dx, dy, behind, fdx, fdy, frontScale, backScale);
            HaoQuang.write(calibFile(), json);
        } catch (Exception ignored) { }
    }

    // ═══════════════════════ HỘP THOẠI ═══════════════════════
    private void info(String msg) {
        JOptionPane.showMessageDialog(this, msg, "Hào Quang", JOptionPane.INFORMATION_MESSAGE);
    }

    private void error2(String title, String msg) {
        JOptionPane.showMessageDialog(this, msg, title, JOptionPane.ERROR_MESSAGE);
    }

    private void error(String title, Exception ex) {
        error2(title, String.valueOf(ex.getMessage()));
    }

    private boolean confirm(String title, String msg) {
        return JOptionPane.showConfirmDialog(this, msg, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }
}
