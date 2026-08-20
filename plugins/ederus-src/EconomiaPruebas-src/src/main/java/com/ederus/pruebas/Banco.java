package com.ederus.pruebas;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La economia de mentira. Va aparte del JavaPlugin porque este ya tiene
 * isEnabled() y getName() como finales y chocan con los de Economy.
 */
public final class Banco implements Economy {

    private final Map<String, Double> saldos = new ConcurrentHashMap<>();
    private volatile boolean roto = false;

    public void romper(boolean v) { roto = v; }
    public boolean roto() { return roto; }
    public void dar(String nombre, double cantidad) { saldos.merge(nombre.toLowerCase(), cantidad, Double::sum); }
    public double ver(String nombre) { return saldos.getOrDefault(nombre.toLowerCase(), 0d); }

    private String clave(OfflinePlayer p) { return p.getName() == null ? "?" : p.getName().toLowerCase(); }

    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) {
        if (roto) return new EconomyResponse(0, getBalance(p), ResponseType.FAILURE, "banco roto (prueba)");
        if (amount < 0) return new EconomyResponse(0, getBalance(p), ResponseType.FAILURE, "cantidad negativa");
        return new EconomyResponse(amount, saldos.merge(clave(p), amount, Double::sum), ResponseType.SUCCESS, null);
    }

    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) {
        if (roto) return new EconomyResponse(0, getBalance(p), ResponseType.FAILURE, "banco roto (prueba)");
        double actual = getBalance(p);
        if (amount < 0 || actual < amount) {
            return new EconomyResponse(0, actual, ResponseType.FAILURE, "saldo insuficiente");
        }
        return new EconomyResponse(amount, saldos.merge(clave(p), -amount, Double::sum), ResponseType.SUCCESS, null);
    }

    @Override public double getBalance(OfflinePlayer p) { return saldos.getOrDefault(clave(p), 0d); }
    @Override public boolean has(OfflinePlayer p, double amount) { return getBalance(p) >= amount; }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "EconomiaPruebas"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return String.format(java.util.Locale.US, "%.2f", amount); }
    @Override public String currencyNamePlural() { return "monedas"; }
    @Override public String currencyNameSingular() { return "moneda"; }
    @Override public boolean hasAccount(OfflinePlayer p) { return true; }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return true; }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String w, double a) { return withdrawPlayer(p, a); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String w, double a) { return depositPlayer(p, a); }

    @Override @Deprecated public boolean hasAccount(String name) { return true; }
    @Override @Deprecated public boolean hasAccount(String name, String world) { return true; }
    @Override @Deprecated public double getBalance(String name) { return ver(name); }
    @Override @Deprecated public double getBalance(String name, String world) { return ver(name); }
    @Override @Deprecated public boolean has(String name, double amount) { return ver(name) >= amount; }
    @Override @Deprecated public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override @Deprecated public EconomyResponse withdrawPlayer(String name, double amount) {
        double actual = ver(name);
        if (roto || actual < amount) return new EconomyResponse(0, actual, ResponseType.FAILURE, "no");
        return new EconomyResponse(amount, saldos.merge(name.toLowerCase(), -amount, Double::sum), ResponseType.SUCCESS, null);
    }
    @Override @Deprecated public EconomyResponse withdrawPlayer(String n, String w, double a) { return withdrawPlayer(n, a); }
    @Override @Deprecated public EconomyResponse depositPlayer(String name, double amount) {
        if (roto) return new EconomyResponse(0, ver(name), ResponseType.FAILURE, "no");
        return new EconomyResponse(amount, saldos.merge(name.toLowerCase(), amount, Double::sum), ResponseType.SUCCESS, null);
    }
    @Override @Deprecated public EconomyResponse depositPlayer(String n, String w, double a) { return depositPlayer(n, a); }
    @Override @Deprecated public boolean createPlayerAccount(String name) { return true; }
    @Override @Deprecated public boolean createPlayerAccount(String name, String world) { return true; }

    @Override public EconomyResponse createBank(String n, String p) { return sinBanco(); }
    @Override public EconomyResponse createBank(String n, OfflinePlayer p) { return sinBanco(); }
    @Override public EconomyResponse deleteBank(String n) { return sinBanco(); }
    @Override public EconomyResponse bankBalance(String n) { return sinBanco(); }
    @Override public EconomyResponse bankHas(String n, double a) { return sinBanco(); }
    @Override public EconomyResponse bankWithdraw(String n, double a) { return sinBanco(); }
    @Override public EconomyResponse bankDeposit(String n, double a) { return sinBanco(); }
    @Override public EconomyResponse isBankOwner(String n, String p) { return sinBanco(); }
    @Override public EconomyResponse isBankOwner(String n, OfflinePlayer p) { return sinBanco(); }
    @Override public EconomyResponse isBankMember(String n, String p) { return sinBanco(); }
    @Override public EconomyResponse isBankMember(String n, OfflinePlayer p) { return sinBanco(); }
    @Override public List<String> getBanks() { return List.of(); }

    private EconomyResponse sinBanco() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "sin bancos");
    }
}
