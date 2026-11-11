package com.cherryjay.aicallagent;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class LogActivity extends Activity {

    private final List<CallLogStore.Item> items = new ArrayList<>();
    private Adapter adapter;
    private View emptyView;
    private TextView title;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        emptyView = findViewById(R.id.tv_empty);
        title = findViewById(R.id.tv_title);
        ListView lv = findViewById(R.id.lv_log);
        adapter = new Adapter();
        lv.setAdapter(adapter);

        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            CallLogStore.clear(this);
            reload();
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items.clear();
        List<CallLogStore.Item> all = CallLogStore.read(this);
        // 倒序：最新的在最上面
        for (int i = all.size() - 1; i >= 0; i--) {
            items.add(all.get(i));
        }
        adapter.notifyDataSetChanged();
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        title.setText(empty ? getString(R.string.log_title) : getString(R.string.log_title) + " · " + items.size());
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(LogActivity.this).inflate(R.layout.item_log, parent, false);
            }
            CallLogStore.Item it = items.get(position);
            TextView tag = v.findViewById(R.id.tv_tag);
            TextView time = v.findViewById(R.id.tv_time);
            TextView text = v.findViewById(R.id.tv_text);

            switch (it.type) {
                case CallLogStore.TYPE_REMOTE:
                    tag.setText("对方");
                    tag.setTextColor(0xFFE8890C);
                    break;
                case CallLogStore.TYPE_LOCAL:
                    tag.setText("我说");
                    tag.setTextColor(0xFF1FAA59);
                    break;
                case CallLogStore.TYPE_ERROR:
                    tag.setText("错误");
                    tag.setTextColor(0xFFD33A2C);
                    break;
                default:
                    tag.setText("信息");
                    tag.setTextColor(0xFF6B7280);
                    break;
            }
            time.setText(it.timeString());
            text.setText(it.text);
            return v;
        }
    }
}
