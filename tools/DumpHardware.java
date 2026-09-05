package dev.c4lam.celeste.auth;

final class DumpHardware {
    private DumpHardware() {
    }

    public static void main(String[] args) {
        HardwareFingerprint hardware = HardwareFingerprint.collect();
        StringBuilder json = new StringBuilder();
        json.append("{\"system\":\"").append(hardware.system())
            .append("\",\"board\":\"").append(hardware.board())
            .append("\",\"processor\":\"").append(hardware.processor())
            .append("\",\"monitors\":[");
        for (int index = 0; index < hardware.monitors().size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(hardware.monitors().get(index)).append('"');
        }
        json.append("]}");
        System.out.println(json);
    }
}
