package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Age (AGE) allocation.
 *
 * Score: S = age_group (feste Werte, keine Normalisierung)
 *
 *   age < 18          → S = 0.0  (Minderjährige)
 *   age > 67          → S = 0.5  (Rentner)
 *   18 ≤ age ≤ 67     → S = 1.0  (Erwerbsalter)
 *
 * Agenten ohne Eintrag in der params-CSV → S = 0.
 *
 * Coins je Agent: coins_i = totalCoins × (S_i / Σ S_i)
 */
public class AgeAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(AgeAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;

    public AgeAllocationCalculator(Map<String, AgentParametersPrecomputer.AgentParams> agentParams) {
        this.agentParams = agentParams;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // Pass 1: Score je Agent per Fallunterscheidung bestimmen
        // Quelle: ap.age (int)
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);

            double score = 0.0;
            if (ap != null) {
                if (ap.age < 18) {
                    score = 0.0;  // Minderjährige
                } else if (ap.age > 67) {
                    score = 0.5;  // Rentner
                } else {
                    score = 1.0;  // Erwerbsalter (18–67)
                }
            }

            scores.put(person.getId(), score);
            totalScore += score;
        }

        // Pass 2: Proportional zu Gesamtscore verteilen
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("AGE: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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
        logger.info("AGE Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return "Age (AGE): S=0 (<18) / 0.5 (>67) / 1.0 (18–67)";
    }
}
