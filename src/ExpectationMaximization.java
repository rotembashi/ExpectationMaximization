import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Stream;

public class ExpectationMaximization {
    private final static double TESTED_LAMBDA = 1.03;
    private final static double EPSILON_THRESHOLD = 0.00000001;
    private final static double K = 10;
    private final static double EM_THRESHOLD = 10; 

    private Map<Integer, List<Article>> clusters;
    private DevelopmentSet developmentSet;
    private Topics topics;
    private int numClusters;
    private Map<Article, Double[]> Wti;
    private Map<Article, Double[]> Zti;
    private Map<Article, Double> Mt;
    private Map<String, Double[]> Pik;
    private double clustersProbability[]; //alpha(i)
    private double lambda;

    public void init(DevelopmentSet developmentSet, int numClusters, Topics topics, double lambda) {
        this.Wti = new HashMap<>();
        this.Zti = new HashMap<>();
        this.Mt = new HashMap<>();
        this.Pik = new HashMap<>();
        this.developmentSet = developmentSet;
        this.topics = topics;
        this.numClusters = numClusters;
        this.clustersProbability = new double[numClusters];
        this.lambda = lambda;

        initClusters();
        initEM();
        MStep();
    }

    public void run() {
    	double lastLikelihood = Double.NEGATIVE_INFINITY;
    	double likelihood = calcLikelihood();
        
        List<Double> likelihoods = new ArrayList<Double>();
        double perplexity = 0;
        List<Double> perplexities = new ArrayList<Double>();
        
        // if in some round
        // we find that the Likelihood decrease - it means that we have a bug in our implementation or
        // that we are smoothing too aggressively.

        // Run EM algorithm until convergence
        while (likelihood - lastLikelihood > EM_THRESHOLD) {
            EStep();
            MStep();

            // Save likelihoods for future graph plot
            if (lastLikelihood == Double.NEGATIVE_INFINITY) {
            	lastLikelihood = calcLikelihood() - 2*EM_THRESHOLD;
            }
            else {
            	lastLikelihood = likelihood;
            }
            likelihood = calcLikelihood();
            likelihoods.add(likelihood);

            // Save perplexities for future graph plot
            perplexity = calcPerplexity(likelihood);
            perplexities.add(perplexity);
            
            System.out.println("likelihood: " + likelihood + "\t" + " perplexity: " + perplexity);

        }            
            
        int[][] confusionMatrix = bulidConfusionMatrix();
        
    }

    private int[][] bulidConfusionMatrix() {
        int[][] confusionMatrix = new int[this.numClusters][this.numClusters + 1];

        for (int[] row : confusionMatrix) {
            Arrays.fill(row, 0);
        }
        
        double correctAssignmentsRate = 0;
        int maxCluster;
        for (Article currentArticle : developmentSet.getArticles()) {
            Double maxWt = Wti.get(currentArticle)[0];
            maxCluster = 0;
            for (int i = 1; i < this.numClusters; i++) {
                Double wti = Wti.get(currentArticle)[i];
                if (wti > maxWt) {
                    maxWt = wti;
                    maxCluster = i;
                }
            }
            currentArticle.setAssignedTopic(topics.getTopics()[maxCluster]);

            // Build the confusion matrix based on the given topics and the max cluster topic
            for (String topic : currentArticle.getTopics()) {
                confusionMatrix[maxCluster][topics.getTopicIndex(topic)] += 1;              
            }
            confusionMatrix[maxCluster][this.numClusters] += 1;
        }
        
        for (int i = 0; i < this.numClusters; i++) {
        	int maxAssignedArticles = confusionMatrix[i][0];
        	for (int j = 1; j < this.numClusters; j++) {
        		if (confusionMatrix[i][j] > maxAssignedArticles) {
        			maxAssignedArticles = confusionMatrix[i][j];
        		}
        	}
        	
        	// Add the percentage of correct assignments for the current cluster
        	correctAssignmentsRate += (double) maxAssignedArticles / confusionMatrix[i][numClusters];
        }
        
        // Calculate the percentage of correct assignments for all clusters
        correctAssignmentsRate /= numClusters;
        
        System.out.println("Accuracy rate is: " + correctAssignmentsRate);

        return confusionMatrix;
    }

    private double calcPerplexity(double likelihood) {
        return Math.pow(2, -1.0 / developmentSet.countNumberOfWords() * likelihood);
    }

    // Set the initial cluster for each article to be the article index modulo number of clusters
    private void initClusters() {
        final int[] index = {0};
        clusters = new HashMap<>();

        developmentSet.getArticles().forEach(article -> {
            int key = index[0]++ % numClusters;

            if (!clusters.containsKey(key)) {
                clusters.put(key, new ArrayList<>());
            }
            clusters.get(key).add(article);
        });
    }

    // Set the initial Wti
    private void initEM() {

        // Going over all articles in each cluster (==all articles) and building the initial clusters probability
        for (int i = 0; i < numClusters; i++) {
            for (Article currentArticle : clusters.get(i)) {
                Double[] clusterProbabilityForArticle = new Double[numClusters];
                for (int j = 0; j < numClusters; j++) {
                    clusterProbabilityForArticle[j] = (i == j ? 1.0 : 0.0);
                }
                Wti.put(currentArticle, clusterProbabilityForArticle);
            }
        }
    }

    private void EStep() {
        for (Article currentArticle : developmentSet.getArticles()) {
            calcWti(currentArticle);
        }
    }

    // Calculate article probabilities for each cluster
    // Approximate Wti (4)
    private void calcWti(Article currentArticle) {
        Double sumZi = 0.0;
        Double[] Zi = calcZi(currentArticle);
        Double[] clusterProbabilityForArticle = new Double[numClusters];
        Double m = calcMaxZi(Stream.of(Zi).mapToDouble(Double::doubleValue).toArray());

        for (int i = 0; i < numClusters; i++) {
            if (Zi[i] - m < -1 * K) {
                clusterProbabilityForArticle[i] = 0.0;
            } else {
                double eZiMinusM = Math.exp(Zi[i] - m);

                clusterProbabilityForArticle[i] = eZiMinusM;
                sumZi += eZiMinusM;
            }
        }

        for (int i = 0; i < numClusters; i++) {
            clusterProbabilityForArticle[i] /= sumZi;
        }

        Wti.put(currentArticle, clusterProbabilityForArticle);
        Zti.put(currentArticle, Zi);
        Mt.put(currentArticle, m);
    }

    // Calculate the Z value for each article in each cluster
    private Double[] calcZi(Article article) {
        Double[] result = new Double[numClusters];

        for (int i = 0; i < numClusters; i++) {
            double sumFrequency = 0;

            // Going over k words and calculate the Z value for each article in each cluster
            for (String word : article.getWordsOccurrences().keySet()) {
                sumFrequency += article.getWordOccurrences(word) * Math.log(Pik.get(word)[i]);
            }

            result[i] = Math.log(clustersProbability[i]) + sumFrequency;
        }

        return result;
    }

    private Double calcMaxZi(double[] Zi) {
        return Arrays.stream(Zi).max().getAsDouble();
    }

    private void MStep() {
        calcPik();
        calcAlpha();
        smoothAlpha();
    }

    private void calcPik() {
        double sumWti;
        double wordsOccurrencesInArticles;
        double[] wordsInClusters = new double[numClusters];

        // Calculate Pik (divisor)
        for (int i = 0; i < numClusters; i++) {
            sumWti = 0;
            for (Article currentArticle : developmentSet.getArticles()) {
                sumWti += (this.Wti.get(currentArticle)[i] * currentArticle.getNumberOfWords());
            }
            wordsInClusters[i] = sumWti;
        }
        // Calculate Pik (dividend)
        // Calculate the Lidstone probability for each word to be in each topic by its Occurrences in all articles
        for (String word : developmentSet.getWordsOccurrences().keySet()) {
            Double[] lidstoneP = new Double[numClusters];
            for (int i = 0; i < numClusters; i++) {
                wordsOccurrencesInArticles = 0;
                for (Article currentArticle : developmentSet.getArticles()) {
                    int wordOccurrences = currentArticle.getWordOccurrences(word);
                    Double Wti = this.Wti.get(currentArticle)[i];
                    if (wordOccurrences > 0 && Wti > 0) {
                        wordsOccurrencesInArticles += (Wti * wordOccurrences);
                    }
                }
                lidstoneP[i] = calcLidstonePortability(wordsOccurrencesInArticles, wordsInClusters[i]);
            }
            this.Pik.put(word, lidstoneP);
        }
    }

    private double calcLidstonePortability(double wordsOccurrencesInArticles, double wordsInCluster) {
        return (wordsOccurrencesInArticles + TESTED_LAMBDA) / (wordsInCluster + TESTED_LAMBDA * this.developmentSet.getWordsOccurrences().size());
    }

    // Calculate alpha(i)
    private void calcAlpha() {
        double currentClusterProbability;
        for (int i = 0; i < numClusters; i++) {
            currentClusterProbability = 0;
            for (Article currentArticle : developmentSet.getArticles()) {
                currentClusterProbability += this.Wti.get(currentArticle)[i];
            }
            this.clustersProbability[i] = currentClusterProbability / developmentSet.getArticles().size();
        }
    }

    private void smoothAlpha() {
        double sumAlpha = 0;

        // Fix alpha(i) to the epsilon threshold
        for (int i = 0; i < numClusters; i++) {
            this.clustersProbability[i] = (clustersProbability[i] > EPSILON_THRESHOLD ? clustersProbability[i] : EPSILON_THRESHOLD);
        }

        // Find total clusters probability
        for (int i = 0; i < numClusters; i++) {
            sumAlpha += this.clustersProbability[i];
        }

        // Find the probability to be in each cluster
        for (int i = 0; i < numClusters; i++) {
            this.clustersProbability[i] /= sumAlpha;
        }
    }

    private double calcLikelihood() {
        double likelihood = 0;

        for (Article currentArticle : Mt.keySet()) {
            double sumZt = 0;
            double Mt = this.Mt.get(currentArticle);
            Double[] clusters = Zti.get(currentArticle);

            if (clusters != null) {
                for (double Zti : clusters) {
                    if (-1 * K <= Zti - Mt) {
                        sumZt += Math.exp(Zti - Mt);
                    }
                }
            }

            likelihood += Mt + Math.log(sumZt);
        }

        return likelihood;
    }
}