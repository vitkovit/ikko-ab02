package com.mw.focus;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.HashMap;
import java.util.Map;

/**
 * A 5x7 dot-matrix font. Each glyph is rendered as a grid of circular dots,
 * matching the IKKO "dotted clock" watch-face aesthetic (white dots on black).
 *
 * Digits + colon are used for the big countdown; A-Z for the phase labels.
 * The colon is a narrow 2-column glyph; everything else is 5 columns wide.
 * All glyphs are 7 rows tall.
 */
final class DotFont {

    static final int ROWS = 7;
    static final int GAP = 2; // blank columns between glyphs so dots don't visually merge
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    private static void put(char c, String... rows) { GLYPHS.put(c, rows); }

    static {
        put('0', "01110","10001","10011","10101","11001","10001","01110");
        put('1', "00100","01100","00100","00100","00100","00100","01110");
        put('2', "01110","10001","00001","00010","00100","01000","11111");
        put('3', "11111","00010","00100","00010","00001","10001","01110");
        put('4', "00010","00110","01010","10010","11111","00010","00010");
        put('5', "11111","10000","11110","00001","00001","10001","01110");
        put('6', "00110","01000","10000","11110","10001","10001","01110");
        put('7', "11111","00001","00010","00100","01000","01000","01000");
        put('8', "01110","10001","10001","01110","10001","10001","01110");
        put('9', "01110","10001","10001","01111","00001","00010","01100");

        put(':', "00","11","11","00","11","11","00");
        put(' ', "00000","00000","00000","00000","00000","00000","00000");

        put('A', "01110","10001","10001","11111","10001","10001","10001");
        put('B', "11110","10001","10001","11110","10001","10001","11110");
        put('C', "01110","10001","10000","10000","10000","10001","01110");
        put('D', "11110","10001","10001","10001","10001","10001","11110");
        put('E', "11111","10000","10000","11110","10000","10000","11111");
        put('F', "11111","10000","10000","11110","10000","10000","10000");
        put('G', "01110","10001","10000","10111","10001","10001","01111");
        put('H', "10001","10001","10001","11111","10001","10001","10001");
        put('I', "01110","00100","00100","00100","00100","00100","01110");
        put('J', "00111","00010","00010","00010","00010","10010","01100");
        put('K', "10001","10010","10100","11000","10100","10010","10001");
        put('L', "10000","10000","10000","10000","10000","10000","11111");
        put('M', "10001","11011","10101","10101","10001","10001","10001");
        put('N', "10001","11001","10101","10011","10001","10001","10001");
        put('O', "01110","10001","10001","10001","10001","10001","01110");
        put('P', "11110","10001","10001","11110","10000","10000","10000");
        put('Q', "01110","10001","10001","10001","10101","10010","01101");
        put('R', "11110","10001","10001","11110","10100","10010","10001");
        put('S', "01111","10000","10000","01110","00001","00001","11110");
        put('T', "11111","00100","00100","00100","00100","00100","00100");
        put('U', "10001","10001","10001","10001","10001","10001","01110");
        put('V', "10001","10001","10001","10001","10001","01010","00100");
        put('W', "10001","10001","10001","10101","10101","11011","10001");
        put('X', "10001","10001","01010","00100","01010","10001","10001");
        put('Y', "10001","10001","01010","00100","00100","00100","00100");
        put('Z', "11111","00001","00010","00100","01000","10000","11111");
    }

    private DotFont() {}

    static String[] glyph(char c) {
        String[] g = GLYPHS.get(Character.toUpperCase(c));
        return g != null ? g : GLYPHS.get(' ');
    }

    static int glyphCols(char c) {
        return glyph(c)[0].length();
    }

    /** Width of a string in pixels for a given dot pitch (1 blank column between glyphs). */
    static float measure(String text, float pitch) {
        float cols = 0;
        for (int i = 0; i < text.length(); i++) {
            cols += glyphCols(text.charAt(i));
            if (i < text.length() - 1) cols += GAP; // inter-glyph gap
        }
        return cols * pitch;
    }

    /**
     * Draw text as dots. (x,y) is the top-left of the dot grid.
     * Each cell is {@code pitch} px; a dot of radius {@code dotR} is centred in lit cells.
     * Cells matching {@code skipRowsMask} bit per row can be skipped (unused here, kept 0).
     */
    static void draw(Canvas canvas, String text, float x, float y, float pitch, float dotR, Paint paint) {
        float penX = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String[] g = glyph(c);
            int cols = g[0].length();
            for (int r = 0; r < ROWS; r++) {
                String row = g[r];
                for (int col = 0; col < cols; col++) {
                    if (row.charAt(col) == '1') {
                        float dcx = penX + col * pitch + pitch / 2f;
                        float dcy = y + r * pitch + pitch / 2f;
                        canvas.drawCircle(dcx, dcy, dotR, paint);
                    }
                }
            }
            penX += (cols + GAP) * pitch;
        }
    }
}
