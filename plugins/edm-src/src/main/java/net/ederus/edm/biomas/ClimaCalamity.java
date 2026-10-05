package net.ederus.edm.biomas;

/**
 * Lo que decide Lethal Biomes sobre el clima en un mundo de Calamity, sin Bukkit (se prueba
 * fuera del servidor). Calamity 1.12 lleva su propio reloj de clima y su mundo no llueve
 * nunca: alli la lluvia de Minecraft no se usa y la ceniza "de lluvia" sigue a la de Calamity.
 */
final class ClimaCalamity {

    private ClimaCalamity() {
    }

    /**
     * Si cae la ceniza de un clima en modo LLUVIA. Fuera de Calamity, si el mundo llueve.
     * En Calamity el mundo no llueve nunca: manda la fase de su reloj (lluvia o tormenta,
     * si; despejado, no). Con su ciclo apagado (fase vacia) vuelve a mandar el mundo. Si no
     * se puede saber la fase, calamity.cenizas-sin-dato: SIEMPRE o NUNCA.
     */
    static boolean cenizaConLluvia(boolean calamity, String fase, boolean mundoLlueve, String sinDato) {
        if (!calamity) return mundoLlueve;
        if (fase == null) return "SIEMPRE".equalsIgnoreCase(sinDato);
        if (fase.isEmpty()) return mundoLlueve;
        return fase.equals("lluvia") || fase.equals("tormenta");
    }
}
