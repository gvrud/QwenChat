package com.example.qwenchat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One screen built in code: chat, documents, model, history, light/dark theme. */
public class MainActivity extends Activity {

    private static final int REQ_DOC = 1;
    private static final int REQ_GGUF = 2;
    private static final int MAX_TOKENS = 384;

    private ChatStore store;
    private ModelStore models;
    private Chat chat;
    private long engine;
    private int ctxSize;
    private volatile boolean[] cancel = new boolean[1];
    private volatile boolean busy;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout messages;
    private ScrollView scroller;
    private LinearLayout attachBar;
    private EditText input;
    private Button send;
    private TextView status;
    private boolean dark;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new ChatStore(this);
        models = new ModelStore(this);
        dark = models.darkTheme();
        List<Chat> saved = store.chats();
        chat = saved.isEmpty() ? store.create() : saved.get(0);
        store.save();

        // A light font scale does not fit 2x text plus the keyboard; keep the layout simple
        // and let the user read the bigger font instead of clipping.
        setContentView(buildUi());
        loadSelectedModel();
    }

    // ---------------- UI ----------------

    private int bg() { return dark ? 0xFF121212 : 0xFFFFFFFF; }
    private int fg() { return dark ? 0xFFEDEDED : 0xFF111111; }
    private int dim() { return dark ? 0xFF9E9E9E : 0xFF555555; }
    private int userBubble() { return dark ? 0xFF2A3F5F : 0xFFE8F0FE; }
    private int botBubble() { return dark ? 0xFF2A2A2A : 0xFFF2F2F2; }

    /** Every menu and dialog follows the same light/dark choice as the main screen. */
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, dark
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private TextView text(String s, float sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(fg());
        return t;
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(8, 8, 8, 0);

        Button menu = new Button(this);
        menu.setText("☰");
        menu.setOnClickListener(v -> showChats());
        bar.addView(menu, new LinearLayout.LayoutParams(-2, -2));

        TextView title = text("Qwen Chat", 40);
        title.setPadding(16, 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

        Button themeBtn = new Button(this);
        themeBtn.setText(dark ? "☀" : "☾");
        themeBtn.setOnClickListener(v -> toggleTheme());
        bar.addView(themeBtn, new LinearLayout.LayoutParams(-2, -2));

        Button settings = new Button(this);
        settings.setText("⚙");
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar);

        status = text("", 24);
        status.setTextColor(dim());
        status.setPadding(24, 0, 24, 8);
        root.addView(status);

        scroller = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(16, 8, 16, 8);
        scroller.addView(messages);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.HORIZONTAL);
        attachBar.setPadding(16, 0, 16, 0);
        root.addView(attachBar);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(8, 4, 8, 12);

        Button attach = new Button(this);
        attach.setText("📎");
        attach.setOnClickListener(v -> pickDocument());
        row.addView(attach, new LinearLayout.LayoutParams(-2, -2));

        input = new EditText(this);
        input.setHint("Спросите что-нибудь");
        input.setTextColor(fg());
        input.setHintTextColor(dim());
        input.setTextSize(30);
        input.setPadding(16, 8, 16, 8);
        input.setMaxLines(4);
        // IME "Send" key: users often expect Enter to submit, not just insert a newline.
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
            }
            return false;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        send = new Button(this);
        send.setText("➤");
        send.setTextSize(30);
        send.setOnClickListener(v -> {
            if (busy) {
                cancel[0] = true;
            } else {
                submit();
            }
        });
        row.addView(send, new LinearLayout.LayoutParams(-2, -2));
        root.addView(row);

        refresh();
        return root;
    }

    private void toggleTheme() {
        dark = !dark;
        models.setDarkTheme(dark);
        recreate();
    }

    /** Reads the field and sends; disabled only while an answer is being generated. */
    private void submit() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "Введите сообщение", Toast.LENGTH_SHORT).show();
            return;
        }
        if (busy) {
            Toast.makeText(this, "Дождитесь ответа или нажмите «Стоп»", Toast.LENGTH_SHORT).show();
            return;
        }
        if (engine == 0) {
            if (!models.modelFile().isFile()) {
                Toast.makeText(this, "Сначала скачайте модель (⚙)", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "Модель ещё загружается — подождите", Toast.LENGTH_SHORT).show();
            return;
        }
        sendMessage(q);
    }

    /** Rebuilds document chips, messages and the status line from the current chat. */
    private void refresh() {
        attachBar.removeAllViews();
        for (Chat.Doc d : chat.docs) {
            TextView chip = text("📄 " + d.name + " ✕", 26);
            chip.setPadding(16, 4, 16, 4);
            Chat.Doc dd = d;
            chip.setOnClickListener(v -> {
                chat.docs.remove(dd);
                store.touch(chat);
                refresh();
            });
            attachBar.addView(chip);
        }

        messages.removeAllViews();
        for (Chat.Msg m : chat.messages) messages.addView(bubble(m.text, Chat.Msg.USER.equals(m.role)));

        if (engine != 0) {
            status.setText(models.selectedQuant().id + " · " + ctxSize + " токенов"
                    + (chat.docs.isEmpty() ? "" : " · документов: " + chat.docs.size()));
        } else {
            status.setText(models.modelFile().isFile() ? "Загружаю модель…" : "Модель не скачана — откройте ⚙");
        }
        send.setText("➤");
        send.setEnabled(true);
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private TextView bubble(String text, boolean user) {
        TextView t = text(text, 30);
        t.setTextIsSelectable(true);
        t.setPadding(24, 16, 24, 16);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(user ? userBubble() : botBubble());
        shape.setCornerRadius(24);
        t.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(user ? 40 : 0, 6, user ? 0 : 40, 6);
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------- model ----------------

    private void loadSelectedModel() {
        File f = models.modelFile();
        if (!f.isFile()) {
            refresh();
            return;
        }
        status.setText("Загружаю модель…");
        final String path = f.getAbsolutePath();
        final int ctx = models.ctx();
        final int threads = models.threads();
        io.execute(() -> {
            long h = 0;
            String err = null;
            try {
                h = Qwen.load(path, ctx, threads, !models.noMmap());
                if (h == 0) {
                    // No mmap fallback for devices where the mapped file cannot be used.
                    h = Qwen.load(path, ctx, threads, false);
                }
            } catch (Throwable t) {
                // A broken .so or a rejected file must not kill the process: report it.
                err = String.valueOf(t);
            }
            final long loaded = h;
            final String failure = err;
            ui.post(() -> {
                if (loaded == 0) {
                    status.setText(failure != null ? "Ошибка загрузки: " + failure
                            : "Не удалось загрузить модель — выберите другое квантование в ⚙");
                    return;
                }
                if (engine != 0) Qwen.free(engine);
                engine = loaded;
                ctxSize = Qwen.ctxSize(loaded);
                refresh();
            });
        });
    }

    private void reloadModel() {
        if (engine != 0) {
            Qwen.free(engine);
            engine = 0;
        }
        refresh();
        loadSelectedModel();
    }

    private void showDownloadDialog() {
        String[] items = new String[ModelStore.QUANTS.length];
        for (int i = 0; i < items.length; i++) {
            ModelStore.Quant q = ModelStore.QUANTS[i];
            items[i] = q.id + " — " + q.note + (models.file(q).isFile() ? "  ✓" : "");
        }
        dialog()
                .setTitle("Модель Qwen2.5-0.5B-Instruct")
                .setItems(items, (d, which) -> download(ModelStore.QUANTS[which]))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void download(ModelStore.Quant q) {
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(1000);
        final boolean[] c = new boolean[1];
        AlertDialog dlg = dialog()
                .setTitle("Загрузка " + q.id)
                .setView(pb)
                .setNegativeButton("Отмена", (d, w) -> c[0] = true)
                .create();
        dlg.show();
        io.execute(() -> {
            try {
                Downloader.download(this, q, (done, total) -> ui.post(() -> {
                    pb.setProgress((int) (1000L * done / Math.max(1, total)));
                    status.setText("Скачано " + done / (1 << 20) + " / " + total / (1 << 20) + " МБ");
                }), c);
                ui.post(() -> {
                    dlg.dismiss();
                    models.setQuant(q.id);
                    reloadModel();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    dlg.dismiss();
                    status.setText("Загрузка прервана");
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void importGguf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_GGUF);
    }

    private void copyGguf(Uri uri) {
        status.setText("Копирую файл модели…");
        final File dst = new File(models.modelsDir(), "custom.gguf");
        io.execute(() -> {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                dst.delete();
                ui.post(() -> Toast.makeText(this, "Не удалось: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            if (!Downloader.isGguf(dst)) {
                dst.delete();
                ui.post(() -> Toast.makeText(this, "Это не файл GGUF", Toast.LENGTH_LONG).show());
                return;
            }
            ui.post(() -> {
                models.setCustom();
                reloadModel();
            });
        });
    }

    // ---------------- documents ----------------

    private void pickDocument() {
        if (engine == 0) {
            Toast.makeText(this, "Сначала скачайте модель (⚙)", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_DOC);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_GGUF) {
            copyGguf(uri);
            return;
        }
        final String name = queryName(uri);
        status.setText("Читаю " + name + "…");
        io.execute(() -> {
            try {
                final String text = DocReader.read(this, uri, name);
                if (text.trim().isEmpty()) throw new Exception("В файле не найден текст");
                ui.post(() -> {
                    chat.docs.add(new Chat.Doc(name, text));
                    store.touch(chat);
                    refresh();
                    askSummarize(text.length());
                });
            } catch (Exception e) {
                ui.post(() -> {
                    status.setText("");
                    refresh();
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void askSummarize(int chars) {
        dialog()
                .setTitle("Документ прикреплён (" + chars + " символов)")
                .setMessage("Составить краткое содержание? Модель на телефоне отвечает медленно, "
                        + "пересказ может занять несколько минут.")
                .setPositiveButton("Кратко о документе", (d, w) ->
                        sendMessage("Кратко перескажи документ: о чём он, главные тезисы и выводы."))
                .setNegativeButton("Спрошу сам", null)
                .show();
    }

    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p == null ? "документ" : p;
    }

    // ---------------- chat ----------------

    private void sendMessage(String q) {
        if (q.isEmpty() || engine == 0 || busy) return;
        input.setText("");
        chat.messages.add(new Chat.Msg(Chat.Msg.USER, q));
        // The first question becomes the chat name in the history list.
        if (chat.messages.size() == 1) chat.title = q.length() > 40 ? q.substring(0, 40) + "…" : q;
        store.touch(chat);

        final Chat active = chat;
        messages.addView(bubble(q, true));
        final TextView answer = bubble("…", false);
        messages.addView(answer);
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));

        busy = true;
        send.setText("Стоп");
        cancel = new boolean[1];
        final boolean[] c = cancel;

        final List<Chat.Msg> history = new ArrayList<>(active.messages);
        history.remove(history.size() - 1);
        final List<Chat.Doc> docs = new ArrayList<>(active.docs);
        final float temp = models.temperature();
        final StringBuilder out = new StringBuilder();

        io.execute(() -> {
            String err = null;
            String full = "";
            final String prompt;
            try {
                prompt = Llm.buildPrompt(engine, docs, history, q, ctxSize, MAX_TOKENS);
                int[] shown = {0};
                full = new Llm(engine).generate(prompt, MAX_TOKENS, temp, c, new Llm.Listener() {
                    @Override
                    public void onToken(String t) {
                        out.append(t);
                        if (out.length() - shown[0] > 24 || out.indexOf("\n") >= shown[0]) {
                            shown[0] = out.length();
                            String s = out.toString();
                            ui.post(() -> {
                                answer.setText(s);
                                scroller.fullScroll(View.FOCUS_DOWN);
                            });
                        }
                    }

                    @Override
                    public void onDone(String error) {
                    }
                });
            } catch (Throwable e) {
                err = "ошибка: " + e;
            }
            finishAnswer(active, answer, out.length() > 0 ? out.toString() : full, err);
        });
    }

    private void finishAnswer(Chat active, TextView answer, String text, String error) {
        final String shown = text.trim().isEmpty()
                ? (error != null ? "(" + error + ")" : cancel[0] ? "(остановлено)" : "(пустой ответ)")
                : text.trim();
        ui.post(() -> {
            busy = false;
            send.setText("➤");
            send.setEnabled(true);
            answer.setText(shown);
            answer.setTextColor(error != null ? 0xFFD32F2F : fg());
            if (!shown.startsWith("(")) active.messages.add(new Chat.Msg(Chat.Msg.BOT, shown));
            try {
                store.touch(active);
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось сохранить историю: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
            if (active == chat) scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ---------------- history ----------------

    private void showChats() {
        final List<Chat> list = store.chats();
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Chat c = list.get(i);
            items[i] = c.title + (c == chat ? "  ●" : "")
                    + (c.docs.isEmpty() ? "" : "  📄" + (c.docs.size() > 1 ? c.docs.size() : ""));
        }
        dialog()
                .setTitle("История чатов")
                .setItems(items, (d, w) -> openChat(list.get(w)))
                .setPositiveButton("Новый чат", (d, w) -> {
                    if (busy) {
                        Toast.makeText(this, "Дождитесь ответа или нажмите «Стоп»", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    chat = store.create();
                    store.save();
                    refresh();
                })
                .setNeutralButton("Удалить", (d, w) -> deleteChatDialog())
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void openChat(Chat c) {
        if (busy) {
            Toast.makeText(this, "Дождитесь ответа или нажмите «Стоп»", Toast.LENGTH_SHORT).show();
            return;
        }
        chat = c;
        refresh();
    }

    private void deleteChatDialog() {
        final List<Chat> list = store.chats();
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) items[i] = list.get(i).title;
        dialog()
                .setTitle("Удалить чат")
                .setItems(items, (d, w) -> {
                    Chat victim = list.get(w);
                    if (busy && victim == chat) {
                        Toast.makeText(this, "Этот чат сейчас отвечает", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    store.delete(victim);
                    if (victim == chat) {
                        List<Chat> left = store.chats();
                        chat = left.isEmpty() ? store.create() : left.get(0);
                        store.save();
                    }
                    refresh();
                })
                .show();
    }

    // ---------------- settings ----------------

    private void showSettings() {
        String[] items = {
                "Скачать модель",
                "Загрузить свой GGUF",
                "Тема: " + (dark ? "тёмная" : "светлая"),
                "Контекст: " + models.ctx() + " токенов",
                "Потоки CPU: " + models.threads(),
                "Температура: " + models.temperature(),
                "mmap: " + (models.noMmap() ? "выкл" : "вкл"),
                "О приложении",
        };
        dialog()
                .setTitle("Настройки")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: showDownloadDialog(); break;
                        case 1: importGguf(); break;
                        case 2: toggleTheme(); break;
                        case 3: pickCtx(); break;
                        case 4: pickThreads(); break;
                        case 5: pickTemp(); break;
                        case 6: models.setNoMmap(!models.noMmap()); reloadModel(); break;
                        default: about(); break;
                    }
                })
                .show();
    }

    private void pickCtx() {
        final List<String> names = new ArrayList<>(ModelStore.CTX_PRESETS.keySet());
        dialog()
                .setTitle("Контекст: меньше — меньше ОЗУ")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    models.setCtx(ModelStore.CTX_PRESETS.get(names.get(w)));
                    reloadModel();
                })
                .show();
    }

    private void pickThreads() {
        String[] items = new String[ModelStore.THREADS.length];
        for (int i = 0; i < items.length; i++) items[i] = String.valueOf(ModelStore.THREADS[i]);
        dialog().setTitle("Потоки CPU")
                .setItems(items, (d, w) -> {
                    models.setThreads(ModelStore.THREADS[w]);
                    reloadModel();
                }).show();
    }

    private void pickTemp() {
        String[] items = {"0.1 — точные ответы", "0.3 — сжатые ответы", "0.7 — среднее", "1.0 — свободнее"};
        float[] vals = {0.1f, 0.3f, 0.7f, 1.0f};
        dialog().setTitle("Температура")
                .setItems(items, (d, w) -> models.setTemperature(vals[w])).show();
    }

    private void about() {
        dialog()
                .setTitle("Qwen Chat")
                .setMessage("Локальный чат с Qwen2.5-0.5B-Instruct (llama.cpp).\n"
                        + "Интернет нужен только для скачивания модели. История и документы "
                        + "хранятся внутри приложения.\n" + Qwen.info())
                .setPositiveButton("OK", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        cancel[0] = true;
        super.onDestroy();
    }
}
