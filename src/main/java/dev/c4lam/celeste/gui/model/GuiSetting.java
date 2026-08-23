package dev.c4lam.celeste.gui.model;

import java.util.List;

public sealed interface GuiSetting permits GuiSetting.Toggle, GuiSetting.Slider, GuiSetting.Mode, GuiSetting.Keybind {
    String label();

    String description();

    final class Toggle implements GuiSetting {
        private final String label;
        private final String description;
        private boolean value;

        public Toggle(String label, String description, boolean value) {
            this.label = label;
            this.description = description;
            this.value = value;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public boolean value() {
            return value;
        }

        public void toggle() {
            value = !value;
        }

        public void setValue(boolean value) {
            this.value = value;
        }
    }

    final class Slider implements GuiSetting {
        private final String label;
        private final String description;
        private final double minimum;
        private final double maximum;
        private final double step;
        private double value;

        public Slider(String label, String description, double value, double minimum, double maximum, double step) {
            this.label = label;
            this.description = description;
            this.minimum = minimum;
            this.maximum = maximum;
            this.step = step;
            setValue(value);
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public double minimum() {
            return minimum;
        }

        public double maximum() {
            return maximum;
        }

        public double step() {
            return step;
        }

        public double value() {
            return value;
        }

        public void setValue(double value) {
            double clamped = Math.max(minimum, Math.min(maximum, value));
            this.value = Math.round(clamped / step) * step;
        }

        public double progress() {
            return (value - minimum) / (maximum - minimum);
        }
    }

    final class Mode implements GuiSetting {
        private final String label;
        private final String description;
        private final List<String> values;
        private int index;

        public Mode(String label, String description, List<String> values, String selected) {
            if (values.isEmpty()) {
                throw new IllegalArgumentException("A mode setting needs at least one option");
            }
            this.label = label;
            this.description = description;
            this.values = List.copyOf(values);
            this.index = Math.max(0, this.values.indexOf(selected));
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public String value() {
            return values.get(index);
        }

        public List<String> values() {
            return values;
        }

        public void setValue(String value) {
            int found = values.indexOf(value);
            if (found >= 0) {
                index = found;
            }
        }

        public void next() {
            index = (index + 1) % values.size();
        }

        public void previous() {
            index = (index - 1 + values.size()) % values.size();
        }
    }

    final class Keybind implements GuiSetting {
        private final String label;
        private final String description;
        private int keyCode;

        public Keybind(String label, String description, int keyCode) {
            this.label = label;
            this.description = description;
            this.keyCode = keyCode;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public String description() {
            return description;
        }

        public int keyCode() {
            return keyCode;
        }

        public void setKeyCode(int keyCode) {
            this.keyCode = keyCode;
        }
    }
}
