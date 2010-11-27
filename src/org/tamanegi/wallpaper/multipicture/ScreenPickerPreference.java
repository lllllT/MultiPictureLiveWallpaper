package org.tamanegi.wallpaper.multipicture;

import android.content.Context;
import android.preference.DialogPreference;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;

public class ScreenPickerPreference extends DialogPreference
{
    public static final int SCREEN_NUMBER_MIN = 1;
    public static final int SCREEN_NUMBER_MAX = 20;
    public static final int SCREEN_COUNT =
        SCREEN_NUMBER_MAX - SCREEN_NUMBER_MIN + 1;

    private int screen_number = SCREEN_NUMBER_MIN;
    private EditText number_text;
    private ScreenPickerListener picker_listener = null;

    public static interface ScreenPickerListener
    {
        public boolean onScreenNumberChanging(int screen_num);
        public void onScreenNumberPicked(int screen_num);
    }

    public ScreenPickerPreference(Context context, AttributeSet attrs)
    {
        super(context, attrs);

        setWidgetLayoutResource(R.layout.preference_add_screen);
        setDialogLayoutResource(R.layout.screen_picker);
        setDialogTitle(R.string.pref_add_screen_dialog_title);
    }

    public int getScreenNumber()
    {
        return screen_number;
    }

    public void setScreenNumber(int num)
    {
        if(num >= SCREEN_NUMBER_MIN && num <= SCREEN_NUMBER_MAX) {
            screen_number = num;
        }

        updateScreenNumber();
    }

    public void setScreenPickedListener(ScreenPickerListener listener)
    {
        picker_listener = listener;
    }

    @Override
    protected void onBindDialogView(View view)
    {
        super.onBindDialogView(view);

        view.findViewById(R.id.screen_picker_decrement).setOnClickListener(
            new OperatorClickListener(-1));
        view.findViewById(R.id.screen_picker_increment).setOnClickListener(
            new OperatorClickListener(+1));

        number_text = (EditText)view.findViewById(R.id.screen_picker_number);
        number_text.setFilters(
            new InputFilter[] { new ScreenNumberInputFilter() });

        updateScreenNumber();
    }

    @Override
    protected void onDialogClosed(boolean positiveResult)
    {
        super.onDialogClosed(positiveResult);

        if(positiveResult && picker_listener != null) {
            picker_listener.onScreenNumberPicked(screen_number);
        }
    }

    private void updateScreenNumber()
    {
        if(number_text != null) {
            number_text.setTextKeepState(String.valueOf(screen_number));
        }
    }

    private class OperatorClickListener implements View.OnClickListener
    {
        private int add_num;

        private OperatorClickListener(int add_num)
        {
            this.add_num = add_num;
        }

        @Override
        public void onClick(View v)
        {
            int num = screen_number + add_num;
            while(num >= SCREEN_NUMBER_MIN && num <= SCREEN_NUMBER_MAX) {
                if(picker_listener == null ||
                   picker_listener.onScreenNumberChanging(num)) {
                    setScreenNumber(num);
                    break;
                }
                else {
                    num += add_num;
                }
            }
        }
    }

    private class ScreenNumberInputFilter implements InputFilter
    {
        @Override
        public CharSequence filter(CharSequence source, int start, int end,
                                   Spanned dest, int dstart, int dend)
        {
            String str =
                dest.subSequence(0, dstart).toString() +
                source.subSequence(start, end).toString() +
                dest.subSequence(dend, dest.length()).toString();
            CharSequence org = dest.subSequence(dstart, dend);

            if(! str.matches("[1-9][0-9]*")) {
                return org;
            }

            try {
                int val = Integer.parseInt(str);
                if(val < SCREEN_NUMBER_MIN || val > SCREEN_NUMBER_MAX) {
                    return org;
                }

                if(picker_listener == null ||
                   picker_listener.onScreenNumberChanging(val)) {
                    screen_number = val;
                }
            }
            catch(NumberFormatException e) {
                return org;
            }

            return null;
        }
    }
}
