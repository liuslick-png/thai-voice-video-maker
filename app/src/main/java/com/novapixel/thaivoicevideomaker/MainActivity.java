package com.novapixel.thaivoicevideomaker;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int PICK_IMAGE = 41;
    private Uri imageUri;
    private ImageView imagePreview;
    private VideoView videoPreview;
    private EditText narration;
    private TextView status;
    private ProgressBar progress;
    private Button createButton, saveButton;
    private TextToSpeech tts;
    private boolean ttsReady;
    private File pendingVideo;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        tts = new TextToSpeech(this, this);
        setContentView(buildUi());
    }

    private View buildUi() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 19, 37));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, dp(36));
        scroll.addView(root);

        TextView title = text("Thai Voice\nVideo Maker", 30, Color.WHITE);
        title.setGravity(Gravity.CENTER); title.setTypeface(null, 1); root.addView(title);
        TextView subtitle = text("สร้างและดูตัวอย่างก่อนบันทึก • ทำงานออฟไลน์", 15, Color.rgb(190, 194, 226));
        subtitle.setGravity(Gravity.CENTER); subtitle.setPadding(0, dp(8), 0, dp(22)); root.addView(subtitle);

        imagePreview = new ImageView(this);
        imagePreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imagePreview.setBackground(round(Color.rgb(40, 45, 76), 24));
        LinearLayout.LayoutParams mediaLp = new LinearLayout.LayoutParams(-1, dp(390));
        mediaLp.bottomMargin = dp(12);
        root.addView(imagePreview, mediaLp);

        videoPreview = new VideoView(this);
        videoPreview.setVisibility(View.GONE);
        videoPreview.setBackgroundColor(Color.BLACK);
        root.addView(videoPreview, new LinearLayout.LayoutParams(-1, dp(390)));
        MediaController controls = new MediaController(this);
        controls.setAnchorView(videoPreview); videoPreview.setMediaController(controls);
        videoPreview.setOnPreparedListener(player -> {
            player.setVolume(1f, 1f);
            player.setLooping(true);
            videoPreview.start();
        });

        Button choose = button("① เลือกภาพบุคคลหน้าตรง", Color.rgb(75, 62, 150));
        choose.setOnClickListener(v -> chooseImage()); root.addView(choose, buttonLp());

        narration = new EditText(this);
        narration.setHint("② ใส่ข้อความภาษาไทยที่ต้องการให้พูด…");
        narration.setHintTextColor(Color.rgb(148, 151, 180)); narration.setTextColor(Color.WHITE);
        narration.setTextSize(17); narration.setGravity(Gravity.TOP);
        narration.setPadding(dp(16), dp(16), dp(16), dp(16)); narration.setMinHeight(dp(140));
        narration.setBackground(round(Color.rgb(31, 35, 60), 18));
        root.addView(narration, new LinearLayout.LayoutParams(-1, -2));

        createButton = button("③ สร้างและเล่นตัวอย่าง", Color.rgb(105, 75, 255));
        LinearLayout.LayoutParams createLp = buttonLp(); createLp.topMargin = dp(18);
        root.addView(createButton, createLp); createButton.setOnClickListener(v -> startCreation());

        saveButton = button("④ บันทึกวิดีโอลงโทรศัพท์", Color.rgb(23, 150, 113));
        saveButton.setVisibility(View.GONE); saveButton.setOnClickListener(v -> saveVideo());
        root.addView(saveButton, buttonLp());

        progress = new ProgressBar(this); progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL; progressLp.topMargin = dp(18); root.addView(progress, progressLp);

        status = text("กำลังเตรียมระบบเสียง…", 14, Color.rgb(194, 198, 228));
        status.setGravity(Gravity.CENTER); status.setPadding(0, dp(12), 0, 0); root.addView(status);
        return scroll;
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMAGE && result == RESULT_OK && data != null) {
            imageUri = data.getData();
            if (imageUri != null) {
                try { getContentResolver().takePersistableUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) {}
                videoPreview.stopPlayback(); videoPreview.setVisibility(View.GONE); imagePreview.setVisibility(View.VISIBLE);
                imagePreview.setImageURI(imageUri); saveButton.setVisibility(View.GONE);
                status.setText("เลือกภาพแล้ว • พร้อมสร้างตัวอย่าง");
            }
        }
    }

    @Override public void onInit(int result) {
        if (result == TextToSpeech.SUCCESS) {
            int language = tts.setLanguage(new Locale("th", "TH"));
            ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
            status.setText(ttsReady ? "ระบบเสียงภาษาไทยพร้อมใช้งาน" : "ไม่พบข้อมูลเสียงภาษาไทยในโทรศัพท์");
        } else status.setText("เริ่มระบบเสียงไม่สำเร็จ");
    }

    private void startCreation() {
        String text = narration.getText().toString().trim();
        if (imageUri == null) { toast("กรุณาเลือกภาพก่อน"); return; }
        if (text.isEmpty()) { toast("กรุณาใส่ข้อความภาษาไทย"); return; }
        if (!ttsReady) { toast("ระบบเสียงภาษาไทยยังไม่พร้อม"); return; }

        saveButton.setVisibility(View.GONE);
        setBusy(true, "กำลังสร้างเสียงบรรยายแบบออฟไลน์…");
        File wav = new File(getCacheDir(), "narration-" + UUID.randomUUID() + ".wav");
        String id = UUID.randomUUID().toString();
        Bundle options = new Bundle();
        options.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        tts.setSpeechRate(0.92f); tts.setPitch(0.88f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {}
            @Override public void onDone(String utteranceId) {
                if (id.equals(utteranceId)) runOnUiThread(() -> renderVideo(wav));
            }
            @Override public void onError(String utteranceId) {
                runOnUiThread(() -> setBusy(false, "สร้างเสียงไม่สำเร็จ กรุณาลองใหม่"));
            }
        });
        if (tts.synthesizeToFile(text, options, wav, id) != TextToSpeech.SUCCESS)
            setBusy(false, "ไม่สามารถเริ่มสร้างเสียงได้");
    }

    private void renderVideo(File wav) {
        setBusy(true, "กำลังสร้างภาพเคลื่อนไหวและรวมเสียง…");
        if (pendingVideo != null) pendingVideo.delete();
        pendingVideo = new File(getCacheDir(), "preview-" + System.currentTimeMillis() + ".mp4");
        VideoRenderer.render(this, imageUri, wav, pendingVideo, new VideoRenderer.Callback() {
            @Override public void onSuccess() {
                wav.delete();
                runOnUiThread(() -> {
                    setBusy(false, "ตัวอย่างพร้อมแล้ว • กดเล่นเพื่อตรวจเสียงและภาพ");
                    imagePreview.setVisibility(View.GONE); videoPreview.setVisibility(View.VISIBLE);
                    videoPreview.setVideoPath(pendingVideo.getAbsolutePath());
                    videoPreview.start(); saveButton.setVisibility(View.VISIBLE);
                });
            }
            @Override public void onError(Exception error) {
                wav.delete();
                runOnUiThread(() -> setBusy(false, "สร้างไม่สำเร็จ: " + error.getMessage()));
            }
        });
    }

    private void saveVideo() {
        if (pendingVideo == null || !pendingVideo.exists()) { toast("ไม่พบวิดีโอตัวอย่าง"); return; }
        setBusy(true, "กำลังบันทึกวิดีโอลงโทรศัพท์…");
        Executors.newSingleThreadExecutor().execute(() -> {
            Uri output = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, "ThaiVoiceVideo_" + System.currentTimeMillis() + ".mp4");
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ThaiVoiceVideoMaker");
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                output = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (output == null) throw new IllegalStateException("สร้างไฟล์ปลายทางไม่ได้");
                try (FileInputStream in = new FileInputStream(pendingVideo); OutputStream out = getContentResolver().openOutputStream(output)) {
                    if (out == null) throw new IllegalStateException("เปิดพื้นที่บันทึกไม่ได้");
                    byte[] buffer = new byte[64 * 1024]; int n;
                    while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                }
                ContentValues done = new ContentValues(); done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(output, done, null, null);
                runOnUiThread(() -> { setBusy(false, "บันทึกแล้วใน Movies/ThaiVoiceVideoMaker"); toast("บันทึกวิดีโอสำเร็จ"); });
            } catch (Exception e) {
                if (output != null) getContentResolver().delete(output, null, null);
                runOnUiThread(() -> setBusy(false, "บันทึกไม่สำเร็จ: " + e.getMessage()));
            }
        });
    }

    private void setBusy(boolean busy, String message) {
        createButton.setEnabled(!busy); saveButton.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE); status.setText(message);
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private Button button(String value, int color) {
        Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE); b.setTextSize(16); b.setAllCaps(false);
        b.setBackground(round(color, 18)); return b;
    }
    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58)); lp.bottomMargin = dp(8); return lp;
    }
    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        videoPreview.stopPlayback();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (pendingVideo != null) pendingVideo.delete();
        super.onDestroy();
    }
}
