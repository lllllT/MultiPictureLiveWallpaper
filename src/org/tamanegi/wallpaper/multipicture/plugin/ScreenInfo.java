package org.tamanegi.wallpaper.multipicture.plugin;

import android.os.Bundle;

/**
 * To identify picture appears which screen.
 */
public class ScreenInfo
{
    private static final String DATA_SCREEN_NUMBER = "screenNumber";
    private static final String DATA_SCREEN_COLUMNS = "screenColumns";
    private static final String DATA_SCREEN_ROWS = "screenRows";
    private static final String DATA_TARGET_COLUMN = "targetColumn";
    private static final String DATA_TARGET_ROW = "targetRow";
    private static final String DATA_SCREEN_WIDTH = "screenWidth";
    private static final String DATA_SCREEN_HEIGHT = "screenHeight";
    private static final String DATA_CHANGE_FREQUENCY = "changeFrequency";

    private int screenNumber;                   // 0..., or -1
    private int screenColumns;                  // 1...
    private int screenRows;                     // 1...
    private int targetColumn;                   // [0, screenColumns - 1]
    private int targetRow;                      // [0, screenRows - 1]
    private int screenWidth;                    // px
    private int screenHeight;                   // px
    private int changeFrequency;                // sec

    /**
     * The screen number.
     * <br>
     * If value greater or equal to 0, it is normal screen.
     * The top left screen is 0, and grows left to right, top to bottom.<br>
     * If value less than 0, it is lock screen.
     */
    public int getScreenNumber()
    {
        return screenNumber;
    }

    public void setScreenNumber(int screenNumber)
    {
        this.screenNumber = screenNumber;
    }

    /** Number of screen columns. */
    public int getScreenColumns()
    {
        return screenColumns;
    }

    public void setScreenColumns(int screenColumns)
    {
        this.screenColumns = screenColumns;
    }

    /** Number of screen rows. */
    public int getScreenRows()
    {
        return screenRows;
    }

    public void setScreenRows(int screenRows)
    {
        this.screenRows = screenRows;
    }

    /** Screen column position. */
    public int getTargetColumn()
    {
        return targetColumn;
    }

    public void setTargetColumn(int targetColumn)
    {
        this.targetColumn = targetColumn;
    }

    /** Screen row position. */
    public int getTargetRow()
    {
        return targetRow;
    }

    public void setTargetRow(int targetRow)
    {
        this.targetRow = targetRow;
    }

    /**
     * Width of screen.
     * The width and height may swap if display orientation changed.
     */
    public int getScreenWidth()
    {
        return screenWidth;
    }

    public void setScreenWidth(int screenWidth)
    {
        this.screenWidth = screenWidth;
    }

    /**
     * Height of screen.
     * The width and height may swap if display orientation changed.
     */
    public int getScreenHeight()
    {
        return screenHeight;
    }

    public void setScreenHeight(int screenHeight)
    {
        this.screenHeight = screenHeight;
    }

    /**
     * The time interval to change pictures automatically.
     * If value greater than 0, interval in second.
     * If value is 0, picture will not change automatically.
     */
    public int getChangeFrequency()
    {
        return changeFrequency;
    }

    public void setChangeFrequency(int changeFrequency)
    {
        this.changeFrequency = changeFrequency;
    }

    Bundle foldToBundle()
    {
        Bundle data = new Bundle();

        data.putInt(DATA_SCREEN_NUMBER, screenNumber);
        data.putInt(DATA_SCREEN_COLUMNS, screenColumns);
        data.putInt(DATA_SCREEN_ROWS, screenRows);
        data.putInt(DATA_TARGET_COLUMN, targetColumn);
        data.putInt(DATA_TARGET_ROW, targetRow);
        data.putInt(DATA_SCREEN_WIDTH, screenWidth);
        data.putInt(DATA_SCREEN_HEIGHT, screenHeight);
        data.putInt(DATA_CHANGE_FREQUENCY, changeFrequency);

        return data;
    }

    static ScreenInfo unfoldFromBundle(Bundle data)
    {
        ScreenInfo info = new ScreenInfo();

        if(data != null) {
            info.setScreenNumber(data.getInt(DATA_SCREEN_NUMBER));
            info.setScreenColumns(data.getInt(DATA_SCREEN_COLUMNS));
            info.setScreenRows(data.getInt(DATA_SCREEN_ROWS));
            info.setTargetColumn(data.getInt(DATA_TARGET_COLUMN));
            info.setTargetRow(data.getInt(DATA_TARGET_ROW));
            info.setScreenWidth(data.getInt(DATA_SCREEN_WIDTH));
            info.setScreenHeight(data.getInt(DATA_SCREEN_HEIGHT));
            info.setChangeFrequency(data.getInt(DATA_CHANGE_FREQUENCY));
        }

        return info;
    }
}
