package local.mystictest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Экран для проверки OCR, прохода касаний и смены реплики. Собирается отдельно от MysticTranslate. */
public class Fixture extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff252039);

        TextView quest = label("任务目标：寻找线索", 22, Color.WHITE);
        FrameLayout.LayoutParams questPosition = new FrameLayout.LayoutParams(-1, dp(55), Gravity.TOP);
        questPosition.topMargin = dp(72);
        root.addView(quest, questPosition);

        TextView item = label("道具详情　　新邮件", 15, 0xffd6aa59);
        FrameLayout.LayoutParams itemPosition = new FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP);
        itemPosition.topMargin = dp(133);
        root.addView(item, itemPosition);

        TextView line = label("你好。我是克莱恩·莫雷蒂。\n请接受任务。", 25, Color.WHITE);
        FrameLayout.LayoutParams dialoguePosition = new FrameLayout.LayoutParams(-1, dp(110), Gravity.BOTTOM);
        dialoguePosition.bottomMargin = dp(40);
        root.addView(line, dialoguePosition);
        line.setOnClickListener(view -> {
            line.setText("今天天气很好。\n任务完成。");
            quest.setText("任务目标：返回旅馆");
            root.setBackgroundColor(0xff20293e);
        });
        setContentView(root);
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
