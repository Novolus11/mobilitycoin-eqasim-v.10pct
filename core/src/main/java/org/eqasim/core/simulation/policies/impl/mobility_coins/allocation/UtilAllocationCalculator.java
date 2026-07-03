package org.eqasim.core.simulation.policies.impl.mobility_coins.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Map;

/**
 * Utilitarianism (UTIL) allocation.
 *
 * Score: S = W1*Sustainable_ModalSplit + W2*PT
 *
 *   Sustainable_ModalSplit = (pt_m + bicycle_m + walk_m) / Gesamt_m
 *   Gesamt_m               = car_m + car_passenger_m + pt_m + bicycle_m + walk_m
 *   PT_ModalSplit          = pt_m / (car_m + car_passenger_m + pt_m)  [nur für PT-Fallunterscheidung]
 *
 *   PT = min-max-normalisierter pt_average_raptor, ggf. invertiert (XNOR-Schema):
 *
 *     PT_ModalSplit > Threshold  UND  PT_AverageRaptor > Threshold  →  normal
 *     PT_ModalSplit > Threshold  UND  PT_AverageRaptor < Threshold  →  invertiert
 *     PT_ModalSplit < Threshold  UND  PT_AverageRaptor > Threshold  →  invertiert
 *     PT_ModalSplit < Threshold  UND  PT_AverageRaptor < Threshold  →  normal
 *
 *   Threshold PT_AverageRaptor : 26,21 % des Maximalwerts aller Agenten (dynamisch)
 *   Threshold PT_ModalSplit     : konfigurierbarer Parameter (Standardwert 100,
 *                                 d.h. bis zur Kalibrierung stets < Threshold)
 *
 * Coins je Agent: coins_a = totalCoins × (S_a / Σ S_a)
 */
public class UtilAllocationCalculator implements AllocationCalculator {

    private static final Logger logger = LogManager.getLogger(UtilAllocationCalculator.class);

    private final Map<String, AgentParametersPrecomputer.AgentParams> agentParams;
    private final double w1;
    private final double w2;
    private final double ptModalSplitThreshold;

    public UtilAllocationCalculator(
            Map<String, AgentParametersPrecomputer.AgentParams> agentParams,
            double w1, double w2,
            double ptModalSplitThreshold) {
        this.agentParams           = agentParams;
        this.w1                    = w1;
        this.w2                    = w2;
        this.ptModalSplitThreshold = ptModalSplitThreshold;
    }

    @Override
    public Map<Id<Person>, Double> calculateAllocations(Population population, double totalCoins) {

        // --- Pass 1: Min/Max für pt_average_raptor bestimmen ---
        double maxPtRaptor = 0.0;
        double minPtRaptor = Double.MAX_VALUE;
        boolean hasPtData  = false;

        for (Person person : population.getPersons().values()) {
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(person.getId().toString());
            if (ap == null || ap.ptAverageRaptor == null) continue;
            double v = ap.ptAverageRaptor;
            hasPtData = true;
            if (v < minPtRaptor) minPtRaptor = v;
            if (v > maxPtRaptor) maxPtRaptor = v;
        }

        if (!hasPtData) {
            logger.warn("UTIL: pt_average_raptor ist für alle Agenten null – PT-Komponente wird 0.0 (neutral).");
            minPtRaptor = 0.0;
        }

        double ptRaptorThreshold = maxPtRaptor * 0.2621;

        logger.info("UTIL: PT_AverageRaptor-Schwellenwert ≤ {} (max={}) | PT_ModalSplit-Schwellenwert = {}",
                String.format("%.1f", ptRaptorThreshold),
                String.format("%.1f", maxPtRaptor),
                String.format("%.4f", ptModalSplitThreshold));

        // --- Pass 2: XNOR-Quadrant je Agent auf Rohwerten bestimmen ---
        // true = normaler Raptor-Wert, false = invertierter Raptor-Wert
        Map<String, Boolean> useNormalMap = new HashMap<>();
        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            if (ap == null || ap.ptAverageRaptor == null) continue;

            double motorizedM = ap.carM + ap.carPassengerM + ap.ptM;
            double ptMs       = motorizedM > 0 ? ap.ptM / motorizedM : 0.0;

            boolean ptMsHigh     = ptMs > ptModalSplitThreshold;
            boolean ptRaptorHigh = ap.ptAverageRaptor > ptRaptorThreshold;
            useNormalMap.put(pid, ptMsHigh == ptRaptorHigh); // XNOR
        }

        // --- Pass 3: Raptor-Werte normalisieren und PT-Score berechnen ---
        Map<String, Double> ptScoreMap = new HashMap<>();
        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);
            Boolean useNormal = useNormalMap.get(pid);
            if (ap == null || ap.ptAverageRaptor == null || useNormal == null) continue;

            double normRaptor = normalize(ap.ptAverageRaptor, minPtRaptor, maxPtRaptor);
            ptScoreMap.put(pid, useNormal ? normRaptor : (1.0 - normRaptor));
        }

        // --- Pass 4: Gesamtscores berechnen ---
        Map<Id<Person>, Double> scores = new HashMap<>();
        double totalScore = 0.0;

        for (Person person : population.getPersons().values()) {
            String pid = person.getId().toString();
            AgentParametersPrecomputer.AgentParams ap = agentParams.get(pid);

            double score;
            if (ap == null) {
                score = 0.0;
            } else {
                double totalM     = ap.carM + ap.carPassengerM + ap.ptM + ap.bicycleM + ap.walkM;
                double sustainableMs = totalM > 0 ? (ap.ptM + ap.bicycleM + ap.walkM) / totalM : 0.0;
                double ptScore       = ptScoreMap.getOrDefault(pid, 0.0);

                score = w1 * sustainableMs + w2 * ptScore;
            }
            scores.put(person.getId(), score);
            totalScore += score;
        }

        // --- Pass 5: Proportional zu Gesamtscore verteilen ---
        Map<Id<Person>, Double> allocations = new HashMap<>();
        if (totalScore <= 0) {
            logger.warn("UTIL: Gesamtscore ist 0 – Fallback auf gleichmäßige Verteilung.");
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

    private double normalize(double value, double min, double max) {
        if (max <= min) return 0.0;
        return (value - min) / (max - min);
    }

    private void logStats(Map<Id<Person>, Double> allocations) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0.0;
        for (double v : allocations.values()) {
            if (v < min) min = v; if (v > max) max = v; sum += v;
        }
        logger.info("UTIL Verteilung: {} Agenten | min={} max={} avg={} total={}",
                allocations.size(),
                String.format("%.3f", min), String.format("%.3f", max),
                String.format("%.3f", sum / allocations.size()),
                String.format("%.1f", sum));
    }

    @Override
    public String getDescription() {
        return String.format(
                "Utilitarianism (UTIL): S = %.1f*SustainableMS + %.1f*PT | PT_MS-Threshold=%.4f",
                w1, w2, ptModalSplitThreshold);
    }
}
