package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Household (HOUSEHOLD) allocation.
 *
 * Score: S = normalize(household_size, min, max)
 *
 * household_size aus der params CSV wird min-max-normalisiert (nicht invertiert):
 * größerer Haushalt → höherer Score → mehr Coins.
 * "5+" wird als 5 interpretiert; unbekannte/leere Werte → 1.
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 */
public class HouseholdAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(HouseholdAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public HouseholdAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // -------------------------------------------------------------------------
        // Pass 1: Haushaltsgrößen einlesen, Min/Max bestimmen
        //
        // Quelle: ap.householdSize (String aus CSV, z.B. "3", "5+", "unknown")
        // parseHouseholdSize("3")      → 3.0
        // parseHouseholdSize("5+")     → 5.0  (Maximum-Kategorie)
        // parseHouseholdSize(null/"")  → 1.0  (Fallback: Einpersonenhaushalt)
        // Agenten ohne agentParams-Eintrag → werden übersprungen (→ hh=null in Pass 2)
        // -------------------------------------------------------------------------
        Map<String, Double> rawHh = new HashMap<>();
        double minHh = Double.MAX_VALUE;
        double maxHh = -Double.MAX_VALUE;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            if (ap == null) continue; // kein params-Eintrag → rawHh enthält pid nicht

            double hh = parseHouseholdSize(ap.householdSize);
            rawHh.put(pid, hh);
            if (hh < minHh) minHh = hh;
            if (hh > maxHh) maxHh = hh;
        }

        // -------------------------------------------------------------------------
        // Pass 2: Min-Max-Normalisierung → Score (nicht invertiert)
        //
        // S = (hh - min) / (max - min)
        // → kleinster Haushalt bekommt S=0.0, größter bekommt S=1.0
        // → kein agentParams-Eintrag (rawHh.get(pid) == null) → score bleibt 0.0
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            Double hh = rawHh.get(pid);
            double score = 0.0;
            if (hh != null) {
                score = (maxHh > minHh) ? (hh - minHh) / (maxHh - minHh) : 0.5;
            }
            // kein agentParams-Eintrag → score bleibt 0.0
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // -------------------------------------------------------------------------
        // Pass 3: Coins proportional zum Score verteilen
        // Formel: coins_i = totalCoins × (S_i / Σ S_i)
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("HOUSEHOLD: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
            double coinsPerPerson = totalCoins / population.getPersons().size();
            for (Person person : population.getPersons().values()) {
                allocations.put(person.getId(), coinsPerPerson);
            }
        } else {
            for (Person person : population.getPersons().values()) {
                double s = scores.getOrDefault(person.getId(), 0.0);
                allocations.put(person.getId(), totalCoins * (s / totalScore));
            }
        }

        logStats(allocations);
        return allocations;
    }

    private static double parseHouseholdSize(String size) {
        if (size == null || size.isBlank()) return 1.0;
        if (size.trim().endsWith("+")) return 5.0;
        try { return Double.parseDouble(size.trim()); }
        catch (NumberFormatException e) { return 1.0; }
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        logger.info("HOUSEHOLD Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return "Household (HOUSEHOLD): S = normalize(household_size) – größere Haushalte erhalten mehr Coins";
    }
}
