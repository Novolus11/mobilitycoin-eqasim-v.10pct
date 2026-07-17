package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Time (TIME) allocation.
 *
 * Score: S = normalize(travel_time_car_carp_pt)
 *
 * Quelle: travel_time_car_carp_pt aus agent_params.csv (Sekunden, Baseline-Reisezeit
 * für car + car_passenger + pt). Min-max normalisiert, nicht invertiert:
 * höhere Reisezeit → höherer Score → mehr Coins.
 *
 * Agenten ohne Eintrag in der params-CSV → Score = 0.
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 */
public class TimeAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(TimeAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public TimeAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // Pass 1: Reisezeiten einlesen, Min/Max bestimmen
        // Quelle: ap.travelTimeCarCarpPtS (Sekunden, identisch zu SE_MN)
        Map<String, Double> rawTime = new HashMap<>();
        double minTime = Double.MAX_VALUE;
        double maxTime = -Double.MAX_VALUE;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            if (ap == null) continue;

            double t = ap.travelTimeCarCarpPtS;
            rawTime.put(pid, t);
            if (t < minTime) minTime = t;
            if (t > maxTime) maxTime = t;
        }

        // Pass 2: Normalisieren → Score (nicht invertiert)
        // S = (t - min) / (max - min)
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            Double t = rawTime.get(pid);
            double score = 0.0;
            if (t != null) {
                score = (maxTime > minTime) ? (t - minTime) / (maxTime - minTime) : 0.5;
            }
            // kein agentParams-Eintrag → score bleibt 0.0
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // Pass 3: Coins proportional zum Score verteilen
        // Formel: coins_i = totalCoins × (S_i / Σ S_i)
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("TIME: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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
        logger.info("TIME Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return "Time (TIME): S = normalize(travel_time_car_carp_pt) – höhere Reisezeit erhält mehr Coins";
    }
}
