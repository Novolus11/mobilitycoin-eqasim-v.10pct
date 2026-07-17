package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Income (INCOME_N) allocation.
 *
 * Score: S = income_inv = 1 - normalize(income_midpoint)
 *
 * Der Mittelpunkt des income-Strings (z.B. "1500-2000" → 1750) wird min-max-normalisiert
 * und anschließend invertiert, sodass niedrige Einkommen einen hohen Score erhalten.
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 *
 * Unbekanntes / leeres Einkommen → Defaultwert 3000 EUR/Monat.
 */
public class IncomeNAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(IncomeNAllocationCalculator.class);

    private static final double DEFAULT_INCOME = 3000.0;

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public IncomeNAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // -------------------------------------------------------------------------
        // Pass 1: Einkommens-Mittelpunkt je Agent berechnen, Min/Max bestimmen
        //
        // Quelle: ap.income (String aus CSV, z.B. "1500-2000")
        // parseMidpoint("1500-2000") → 1750.0  (Durchschnitt von lo und hi)
        // parseMidpoint("5000+")     → 6000.0  (untere Grenze + 1000)
        // Unbekannt / leer / kein agentParams-Eintrag → DEFAULT_INCOME = 3000 EUR
        // -------------------------------------------------------------------------
        Map<String, Double> midpoints = new HashMap<>();
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            double mid = DEFAULT_INCOME; // Fallback wenn kein Einkommenseintrag
            if (ap != null && ap.income != null && !ap.income.isBlank()) {
                double parsed = HeSocioAllocationCalculator.parseMidpoint(ap.income);
                if (parsed > 0) mid = parsed; // nur gültige Mittelpunkte übernehmen
            }
            midpoints.put(pid, mid);
            if (mid < min) min = mid;
            if (mid > max) max = mid;
        }

        // -------------------------------------------------------------------------
        // Pass 2: Min-Max-Normalisierung + Invertierung → Score
        //
        // norm  = (mid - min) / (max - min)  → 0.0 für geringstes, 1.0 für höchstes Einkommen
        // score = 1 - norm                   → Invertierung: geringstes Einkommen bekommt S=1.0
        //
        // Ergebnis: niedrige Einkommen → hoher Score → mehr Coins
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            double mid = midpoints.getOrDefault(pid, DEFAULT_INCOME);
            double norm = (max > min) ? (mid - min) / (max - min) : 0.5;
            double score = 1.0 - norm;
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // -------------------------------------------------------------------------
        // Pass 3: Coins proportional zum Score verteilen
        // Formel: coins_i = totalCoins × (S_i / Σ S_i)
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("INCOME_N: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        logger.info("INCOME_N Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return "Income (INCOME_N): S = 1 - normalize(income_midpoint) – niedrige Einkommen erhalten mehr Coins";
    }
}
