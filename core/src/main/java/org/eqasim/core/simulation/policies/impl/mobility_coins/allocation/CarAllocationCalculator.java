package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Car (CAR) allocation.
 *
 * Score: S = car_available
 *
 *   car_availability = "none"            → S = 0.0
 *   car_availability = "all", hh_size=1  → S = 0.125
 *   car_availability = "all", hh_size>1  → S = normalize(household_size, 1, 5) = (hh - 1) / 4
 *
 * Ergibt: 1 → 0.125, 2 → 0.25, 3 → 0.5, 4 → 0.75, 5+ → 1.0
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 */
public class CarAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(CarAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public CarAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // -------------------------------------------------------------------------
        // Pass 1: Score je Agent berechnen
        // Jede Person wird in der agentParams-Map nachgeschlagen (Schlüssel = Person-ID als String).
        // Wenn car_availability = "all": Score aus household_size ableiten.
        // Wenn car_availability = "none" ODER kein Eintrag in der Map: Score = 0.0.
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            // Person-ID als String – muss exakt mit dem Schlüssel in der agentParams-Map übereinstimmen
            String pid = person.getId().toString();

            // Agenten-Parameter aus der vorberechneten CSV nachschlagen
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);

            double score = 0.0;
            if (ap != null && "all".equalsIgnoreCase(ap.carAvailability)) {
                // Person hat ein Auto → Score basiert auf Haushaltsgröße (household_size aus CSV)
                int hh = parseHouseholdSize(ap.householdSize);
                // hh=1: Sonderfall, 0.125 (damit Einpersonenhaushalte nicht leer ausgehen)
                // hh>1: Min-Max-Normalisierung über fixen Bereich [1, 5] → (hh-1)/4
                //        hh=2 → 0.25, hh=3 → 0.5, hh=4 → 0.75, hh=5 → 1.0
                score = (hh == 1) ? 0.125 : (hh - 1) / 4.0;
            }
            // car_availability = "none" oder kein agentParams-Eintrag → score bleibt 0.0

            scores.put(person.getId(), score);
            totalScore += score;  // Summe aller Scores für die proportionale Verteilung
        }

        // -------------------------------------------------------------------------
        // Pass 2: Coins proportional zum Score verteilen
        // Formel: coins_i = totalCoins × (S_i / Σ S_i)
        // Agenten mit score=0 bekommen 0 Coins.
        // Fallback auf Gleichverteilung wenn totalScore=0 (alle haben score=0).
        // -------------------------------------------------------------------------
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("CAR: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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

    // Parst den household_size-String aus der CSV ("1", "2", ..., "5+") zu einem int.
    // Werte > 5 werden auf 5 gekappt; unbekannte/leere Werte → 1.
    private static int parseHouseholdSize(String size) {
        if (size == null || size.isBlank()) return 1;
        if ("5+".equals(size.trim())) return 5;
        try {
            int n = Integer.parseInt(size.trim());
            return Math.min(Math.max(n, 1), 5);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        logger.info("CAR Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return "Car (CAR): S = normalize(household_size, 1, 5) wenn car=all, sonst 0";
    }
}
