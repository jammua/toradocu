package org.toradocu.translator;

import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SentenceParser} parses the {@code edu.stanford.nlp.semgraph.SemanticGraph} of a sentence
 * into a {@code PropositionSeries} through the method {@code getPropositionSeries}.
 * This class uses NLP to identify subjects, relations, conjunctions, and so on from the sentence.
 * <p>This class makes an extensive use of the Stanford parser APIs. The basic thing to know is that
 * an {@code IndexedWord} represents a word in a sentence; a grammatical relation has a governor
 * (or a head) and a dependent. The sentence "she looks beautiful" contains a nsubj (subject)
 * relation between "she" and "looks", where "looks" is the governor and "she" is the dependent.
 */
public class SentenceParser {

  /** The semantic graph of the sentence from which the proposition series will be derived. */
  private SemanticGraph semanticGraph;
  /** Grammatical relations that are extracted from the semantic graph. */
  private List<SemanticGraphEdge>
      subjectRelations,
      copulaRelations,
      complementRelations,
      conjunctionRelations,
      negationRelations;
  /** Logger for this class. */
  private static final Logger log = LoggerFactory.getLogger(SentenceParser.class);

  /**
   * Constructs a {@code SentenceParser} object that will parse the given {@code SemanticGraph}
   * into a {@code PropositionSeries}.
   *
   * @param semanticGraph the {@code SemanticGraph} that will be used to create the
   * {@code PropositionSeries}
   */
  public SentenceParser(SemanticGraph semanticGraph) {
    this.semanticGraph = semanticGraph;
    initializeRelations();
  }

  /**
   * Parses and returns a {@code PropositionSeries} for the sentence that this parser is initialized
   * to parse.
   *
   * @return a {@code PropositionSeries} for the sentence that this parser is initialized to parse
   */
  public PropositionSeries getPropositionSeries() {
    // Map between some of the words in a sentence and the proposition composed of those words.
    // (IndexedWord is a Stanford parser class representing a word in a sentence, plus many other
    // information).
    // This map is used to understand which propositions a conjunction is joining, since we have to
    // map a conjunction between two specific words to a conjunction between two propositions.
    Map<List<IndexedWord>, Proposition> propositionMap = new HashMap<>();
    // Proposition series that will be built and returned.
    PropositionSeries propositionSeries = new PropositionSeries();

    for (SemanticGraphEdge subjectRelation : subjectRelations) {
      // Stores the word marked as a subject word in the semantic graph.
      IndexedWord subject = subjectRelation.getDependent();
      // Stores the subject and associated words, such as any modifiers that come before it.
      // Words (but the subject) appear in the list in the same order as they appear in the
      // sentence. Subject is always the last word in the list.
      List<IndexedWord> subjectWords = getSubjectWords(subject);

      // Get the words that make up the predicate.
      List<IndexedWord> predicateWords = getPredicateWords(subjectRelation.getGovernor());
      if (predicateWords.isEmpty()) {
        // Skip creating a Proposition if no predicate could be identified.
        continue;
      }
      // Check if the predicate should be negated.
      boolean negative = predicateIsNegative(subjectRelation.getGovernor());

      // Create a Proposition from the subject and predicate words.
      String subjectWordsAsString =
          subjectWords.stream().map(w -> w.word()).collect(Collectors.joining(" "));
      String predicateWordsAsString =
          predicateWords.stream().map(w -> w.word()).collect(Collectors.joining(" "));
      Proposition proposition =
          new Proposition(subjectWordsAsString, predicateWordsAsString, negative);
      // Add the Proposition and associated words to the propositionMap.
      List<IndexedWord> propositionWords = new ArrayList<>(subjectWords);
      propositionWords.addAll(predicateWords);
      propositionMap.put(propositionWords, proposition);
    }

    // Identify propositions associated with each conjunction and add them to the propositionSeries.
    for (SemanticGraphEdge conjunctionRelation : conjunctionRelations) {
      IndexedWord conjGovernor = conjunctionRelation.getGovernor();
      IndexedWord conjDependent = conjunctionRelation.getDependent();
      Proposition p1 = null, p2 = null;

      for (Entry<List<IndexedWord>, Proposition> entry : propositionMap.entrySet()) {
        if (entry.getKey().contains(conjGovernor)) {
          p1 = entry.getValue();
        }
        if (entry.getKey().contains(conjDependent)) {
          p2 = entry.getValue();
        }
      }

      if (p1 != null && p2 != null) {
        if (propositionSeries.isEmpty()) {
          propositionSeries.add(p1);
        }
        propositionSeries.add(getConjunction(conjunctionRelation), p2);
      }
    }

    // Add any propositions not part of a conjunction relation to the propositionSeries.
    for (Proposition p : propositionMap.values()) {
      if (!propositionSeries.contains(p)) {
        if (propositionSeries.isEmpty()) {
          propositionSeries.add(p);
        } else {
          // We assume OR as the conjunction when it is not specified.
          propositionSeries.add(Conjunction.OR, p);
        }
      }
    }

    return propositionSeries;
  }

  /**
   * Returns the type of conjunction associated with a conjunction relation.
   *
   * @param conjunctionRelation the relation for which to retrieve the conjunction type
   * @return the conjunction type associated with the given relation
   */
  private Conjunction getConjunction(SemanticGraphEdge conjunctionRelation) {
    String conjunctionRelationSpecific = conjunctionRelation.getRelation().getSpecific();
    Conjunction operator;
    switch (conjunctionRelationSpecific) {
      case "or":
        operator = Conjunction.OR;
        break;
      case "and":
        operator = Conjunction.AND;
        break;
      default:
        operator = null;
    }
    return operator;
  }

  /**
   * Takes the governor for a subject relation and returns true if the predicate (associated with
   * the subject in the subject relation) has a negation modifier.
   *
   * @param governor the governor for a subject relation
   * @return true if the predicate associated with the subject in the subject relation has a
   * negation modifier, false otherwise
   */
  public boolean predicateIsNegative(IndexedWord governor) {
    // Return true if there are an odd number of negation modifiers.
    long numNegEdges =
        negationRelations.stream().filter(e -> e.getGovernor().equals(governor)).count();
    return numNegEdges % 2 == 1;
  }

  /**
   * Takes the governor for a subject relation and returns all words that are part of the predicate
   * associated with the subject in the subject relation.
   *
   * @param governor the governor for a subject relation
   * @return all words in the predicate associated with the subject in the subject relation
   */
  public List<IndexedWord> getPredicateWords(IndexedWord governor) {
    List<IndexedWord> result;

    result = tryCopulaPredicate(governor);
    if (result.isEmpty()) {
      result = tryPassivePredicate(governor);
    }
    if (result.isEmpty()) {
      result = tryNonCopulaPredicate(governor);
    }
    if (result.isEmpty()) {
      result = tryConjunctionPredicate(governor);
    }
    if (result.isEmpty()) {
      log.warn(
          "Unable to identify a predicate (governor = " + governor.word() + ") in \"{}\"",
          semanticGraph.toRecoveredSentenceString());
    }

    return result;
  }

  /**
   * Attempts to return the predicate words associated with the given governor, under the assumption
   * that the predicate is of the non-copula form.
   *
   * @param governor the governor associated with the predicate
   * @return the predicate words or an empty list if the predicate is not of the non-copula form
   */
  private List<IndexedWord> tryNonCopulaPredicate(IndexedWord governor) {
    List<IndexedWord> predicateWords = new ArrayList<>();

    Optional<SemanticGraphEdge> complementEdge =
        complementRelations.stream().filter(e -> e.getGovernor().equals(governor)).findFirst();
    if (!complementEdge.isPresent()) {
      // Predicate is not of non-copula form.
      return predicateWords;
    }
    predicateWords.add(governor);
    predicateWords.add(complementEdge.get().getDependent());

    return predicateWords;
  }

  /**
   * Attempts to return the predicate words associated with the given governor, under the assumption
   * that the predicate is of the conjunction form.
   *
   * @param governor the governor associated with the predicate
   * @return the predicate words or an empty list if the predicate is not of the conjunction form
   */
  private List<IndexedWord> tryConjunctionPredicate(IndexedWord governor) {
    List<IndexedWord> predicateWords = new ArrayList<>();

    // Case 1: conjunction between verbs (e.g., set is OR contains null).
    Optional<SemanticGraphEdge> conjunctionEdge1 =
        conjunctionRelations.stream().filter(e -> e.getGovernor().equals(governor)).findFirst();
    if (conjunctionEdge1.isPresent()) {
      Optional<SemanticGraphEdge> complementEdge =
          complementRelations
              .stream()
              .filter(e -> e.getGovernor().equals(conjunctionEdge1.get().getDependent()))
              .findFirst();
      if (complementEdge.isPresent()) {
        predicateWords.add(governor);
        predicateWords.add(complementEdge.get().getDependent());
        return predicateWords;
      }
    }
    // Case 2: conjunction between complements when there is a copula (e.g., name is empty or null).
    Optional<SemanticGraphEdge> conjunctionEdge2 =
        conjunctionRelations.stream().filter(e -> e.getDependent().equals(governor)).findFirst();
    if (conjunctionEdge2.isPresent()) {
      Optional<SemanticGraphEdge> copulaEdge =
          copulaRelations
              .stream()
              .filter(e -> e.getGovernor().equals(conjunctionEdge2.get().getGovernor()))
              .findFirst();
      if (copulaEdge.isPresent()) {
        predicateWords.add(copulaEdge.get().getDependent());
        predicateWords.add(conjunctionEdge2.get().getDependent());
        return predicateWords;
      }
    }

    return predicateWords;
  }

  /**
   * Attempts to return the predicate words associated with the given governor, under the assumption
   * that the predicate is of the passive form.
   *
   * @param governor the governor associated with the predicate
   * @return the predicate words or an empty list if the predicate is not of the passive form
   */
  private List<IndexedWord> tryPassivePredicate(IndexedWord governor) {
    List<IndexedWord> predicateWords = new ArrayList<>();

    Optional<SemanticGraphEdge> auxpassEdge =
        getRelationsFromGraph("auxpass")
            .stream()
            .filter(e -> e.getGovernor().equals(governor))
            .findFirst();
    if (!auxpassEdge.isPresent()) {
      // Predicate is not of passive form.
      return predicateWords;
    }
    predicateWords.add(auxpassEdge.get().getDependent());
    predicateWords.add(governor);

    return predicateWords;
  }

  /**
   * Attempts to return the predicate words associated with the given governor, under the assumption
   * that the predicate is of the copula form.
   *
   * @param governor the governor associated with the predicate
   * @return the predicate words or an empty list if the predicate is not of the copula form
   */
  private List<IndexedWord> tryCopulaPredicate(IndexedWord governor) {
    List<IndexedWord> predicateWords = new ArrayList<>();

    // For a predicate of this form, the given governor of the subject relation is also the governor
    // of the copula relation.
    Optional<SemanticGraphEdge> copEdge =
        copulaRelations.stream().filter(e -> e.getGovernor().equals(governor)).findFirst();
    if (!copEdge.isPresent()) {
      // Predicate is not of copula form.
      return predicateWords;
    }
    predicateWords.add(copEdge.get().getDependent());
    // Add the governor itself to the predicate.
    predicateWords.add(governor);

    return predicateWords;
  }

  /**
   * Returns all the words that are associated with the subject of the predicate minus any articles.
   * For example, in "the large numbers map is not null", the returned words associated with the
   * subject word "map" are ["large", "numbers", "map"]. "the" is not in the returned words since it
   * is a definite article.
   *
   * @param subject the subject word for which to return the associated words
   * @return the words associated with the given subject (not including articles)
   */
  private List<IndexedWord> getSubjectWords(IndexedWord subject) {
    List<String> ARTICLES = Arrays.asList("a", "an", "the");
    List<IndexedWord> result =
        getRelationsFromGraph("compound", "det", "advmod", "amod", "nmod:poss")
            .stream()
            .filter(e -> e.getGovernor().equals(subject))
            .filter(e -> !ARTICLES.contains(e.getDependent().word()))
            .map(e -> e.getDependent())
            .sorted((w1, w2) -> Integer.compare(w1.index(), w2.index()))
            .collect(Collectors.toList());
    result.add(subject); // TODO: Why we assume that the subject is the last word?
    return result;
  }

  /**
   * Initializes the relations fields using the semantic graph.
   */
  private void initializeRelations() {
    subjectRelations = getRelationsFromGraph("nsubj", "nsubjpass");
    if (subjectRelations.isEmpty()) {
      log.warn("Unable to identify subjects in \"{}\".", semanticGraph.toRecoveredSentenceString());
    }
    copulaRelations = getRelationsFromGraph("cop");
    complementRelations = getRelationsFromGraph("acomp", "xcomp", "dobj");
    conjunctionRelations = getRelationsFromGraph("conj");
    negationRelations = getRelationsFromGraph("neg");
  }

  /**
   * Retrieves relations with the given names from the semantic graph.
   *
   * @param relationShortNames the abbreviated names of the relations to retrieve
   * @return all the grammatical relations in the sentence (as graph edges) that match the specified
   * relation names
   */
  private List<SemanticGraphEdge> getRelationsFromGraph(String... relationShortNames) {
    List<String> relations = Arrays.asList(relationShortNames);
    Stream<SemanticGraphEdge> stream =
        StreamSupport.stream(semanticGraph.edgeIterable().spliterator(), false);
    return stream
        .filter(e -> relations.contains(e.getRelation().getShortName()))
        .collect(Collectors.toList());
  }
}
