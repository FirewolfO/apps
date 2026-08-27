package com.inkriver.historyreader.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import com.inkriver.historyreader.data.Book;

public final class BookCoverView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF coverBounds = new RectF();
    private final RectF sealBounds = new RectF();
    private Book book;

    public BookCoverView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    }

    public void setBook(Book book) {
        this.book = book;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desiredHeight = Math.round(width * 1.34f);
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (book == null) return;
        float w = getWidth();
        float h = getHeight();
        paint.setShadowLayer(Ui.dp(getContext(), 6), 0, Ui.dp(getContext(), 3), 0x28000000);
        paint.setColor(book.accent);
        coverBounds.set(5, 5, w - 5, h - 5);
        canvas.drawRoundRect(coverBounds, Ui.dp(getContext(), 4), Ui.dp(getContext(), 4), paint);
        paint.clearShadowLayer();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(0x88F5F1E8);
        canvas.drawRect(Ui.dp(getContext(), 12), Ui.dp(getContext(), 12),
                w - Ui.dp(getContext(), 12), h - Ui.dp(getContext(), 12), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setColor(0x26FFFFFF);
        canvas.drawRect(Ui.dp(getContext(), 18), 0, Ui.dp(getContext(), 21), h, paint);

        String title = book.title;
        paint.setColor(Color.rgb(250, 246, 236));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        float size = title.length() > 4 ? Ui.dp(getContext(), 22) : Ui.dp(getContext(), 25);
        paint.setTextSize(size);
        float line = size * 1.12f;
        float start = h / 2f - line * (title.length() - 1) / 2f;
        for (int i = 0; i < title.length(); i++) {
            canvas.drawText(title.substring(i, i + 1), w / 2f, start + i * line, paint);
        }
        paint.setFakeBoldText(false);

        float seal = Ui.dp(getContext(), 28);
        float left = w - Ui.dp(getContext(), 18) - seal;
        float top = h - Ui.dp(getContext(), 18) - seal;
        paint.setColor(0xE6F5F1E8);
        sealBounds.set(left, top, left + seal, top + seal);
        canvas.drawRoundRect(sealBounds, Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), paint);
        paint.setTextSize(Ui.dp(getContext(), 11));
        paint.setColor(book.accent);
        paint.setFakeBoldText(true);
        canvas.drawText(book.edition == Book.Edition.ORIGINAL ? "原" :
                book.edition == Book.Edition.VERNACULAR ? "译" : "私", left + seal / 2f, top + seal * .68f, paint);
    }
}
