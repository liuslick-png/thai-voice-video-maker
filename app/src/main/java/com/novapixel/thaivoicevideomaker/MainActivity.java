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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int PICK_IMAGE = 41;
    private Uri imageUri;
    private ImageView preview;
    private EditText narration;
    private TextView status;
    private ProgressBar progress;
    private Button createButton;
    private TextToSpeech tts;
    private boolean ttsReady;

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
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, 1);
        root.addView(title);

        TextView subtitle = text("สร้างวิดีโอผู้บรรยายแบบออฟไลน์ • รุ่นทดสอบ", 15, Color.rgb(190, 194, 226));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(8), 0, dp(22));
        root.addView(subtitle);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackground(round(Color.rgb(40, 45, 76), 24));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(330));
        previewLp.bottomMargin = dp(12);
        root.addView(preview, previewLp);

        Button choose = button("① เลือกภาพบุคคลหน้าตรง", Color.rgb(75, 62, 150));
        choose.setOnClickListener(v -> chooseImage());
        root.addView(choose, buttonLp());

        TextView hint = text("แนะนำ: ภาพครึ่งตัว หน้าหันตรง แสงสม่ำเสมอ", 13, Color.rgb(168, 172, 204));
        hint.setPadding(dp(4), dp(4), dp(4), dp(14));
        root.addView(hint);

        narration = new EditText(this);
        narration.setHint("② ใส่ข้อความภาษาไทยที่ต้องการให้พูด…");
        narration.setHintTextColor(Color.rgb(148, 151, 180));
        narration.setTextColor(Color.WHITE);
        narration.setTextSize(17);
        narration.setGravity(Gravity.TOP);
        narration.setPadding(dp(16), dp(16), dp(16), dp(16));
        narration.setMinHeight(dp(150));
        narration.setBackground(round(Color.rgb(31, 35, 60), 18));
        root.addView(narration, new LinearLayout.LayoutParams(-1, -2));

        createButton = button("③ สร้างวิดีโอ MP4 9:16", Color.rgb(105, 75, 255));
        LinearLayout.LayoutParams createLp = buttonLp();
        createLp.topMargin = dp(18);
        root.addView(createButton, createLp);
        createButton.setOnClickListener(v -> startCreation());

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.topMargin = dp(18);
        root.addView(progress, progressLp);

        status = text("กำลังเตรียมระบบเสียง…", 14, Color.rgb(194, 198, 228));
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, 0);
        root.addView(status);
        return scroll;
    }

    private void chooseImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_IMAGE && result == RESULT_OK && data != null) {
            imageUri = data.getData();
            if (imageUri != null) {
                try { getContentResolver().takePersistableUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) {}
                preview.setImageURI(imageUri);
                status.setText("เลือกภาพแล้ว • พร้อมสร้างวิดีโอ");
            }
        }
    }

    @Override public void onInit(int result) {
        if (result == TextToSpeech.SUCCESS) {
            int language = tts.setLanguage(new Locale("th", "TH"));
            ttsReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED;
            status.setText(ttsReady ? "ระบบเสียงภาษาไทยพร้อมใช้งาน" : "ไม่พบข้อมูลเสียงภาษาไทยในโทรศัพท์");
        } else {
            status.setText("เริ่มระบบเสียงไม่สำเร็จ");
        }
    }

    private void startCreation() {
        String text = narration.getText().toString().trim();
        if (imageUri == null) { Toast.makeText(this, "กรุณาเลือกภาพก่อน", Toast.LENGTH_SHORT).show(); return; }
        if (text.isEmpty()) { Toast.makeText(this, "กรุณาใส่ข้อความภาษาไทย", Toast.LENGTH_SHORT).show(); return; }
        if (!ttsReady) { Toast.makeText(this, "ระบบเสียงภาษาไทยยังไม่พร้อม", Toast.LENGTH_LONG).show(); return; }

        setBusy(true, "กำลังสร้างเสียงบรรยายแบบออฟไลน์…");
        File wav = new File(getCacheDir(), "narration-" + UUID.randomUUID() + ".wav");
        String utteranceId = UUID.randomUUID().toString();

        tts.setSpeechRate(0.92f);
        tts.setPitch(0.88f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) {
                if (!utteranceId.equals(id)) return;
                runOnUiThread(() -> renderVideo(wav));
            }
            @Override public void onError(String id) {
                runOnUiThread(() -> setBusy(false, "สร้างเสียงไม่สำเร็จ กรุณาลองใหม่"));
            }
        });
        int result = tts.synthesizeToFile(text, new Bundle(), wav, utteranceId);
        if (result != TextToSpeech.SUCCESS) setBusy(false, "ไม่สามารถเริ่มสร้างเสียงได้");
    }

    private void renderVideo(File wav) {
        setBusy(true, "กำลังเข้ารหัสวิดีโอ 9:16 • กรุณารอสักครู่…");
        ContentValues values = new ContentValues();
        String filename = "ThaiVoiceVideo_" + System.currentTimeMillis() + ".mp4";
        values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ThaiVoiceVideoMaker");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri output = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (output == null) { setBusy(false, "ไม่สามารถสร้างไฟล์ปลายทางได้"); return; }

        VideoRenderer.render(this, imageUri, wav, output, new VideoRenderer.Callback() {
            @Override public void onSuccess() {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(output, done, null, null);
                wav.delete();
                runOnUiThread(() -> {
                    setBusy(false, "สำเร็จ! บันทึกใน Movies/ThaiVoiceVideoMaker");
                    Toast.makeText(MainActivity.this, "สร้างวิดีโอสำเร็จ", Toast.LENGTH_LONG).show();
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(output, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(view); } catch (Exception ignored) {}
                });
            }
            @Override public void onError(Exception error) {
                getContentResolver().delete(output, null, null);
                wav.delete();
                runOnUiThread(() -> setBusy(false, "สร้างไม่สำเร็จ: " + error.getMessage()));
            }
        });
    }

    private void setBusy(boolean busy, String message) {
        createButton.setEnabled(!busy);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        status.setText(message);
    }

    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); return v;
    }

    private Button button(String value, int color) {
        Button b = new Button(this); b.setText(value); b.setTextColor(Color.WHITE); b.setTextSize(16); b.setAllCaps(false);
        b.setBackground(round(color, 18)); return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58)); lp.bottomMargin = dp(6); return lp;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
