package io.arabesque.embedding;

import io.arabesque.conf.Configuration;
import io.arabesque.computation.Computation;
import io.arabesque.graph.Vertex;
import io.arabesque.graph.Edge;
import io.arabesque.graph.LabelledEdge;
import io.arabesque.misc.WritableObject;
import io.arabesque.pattern.Pattern;
import io.arabesque.pattern.PatternEdge;
import io.arabesque.utils.collection.IntArrayList;
import io.arabesque.utils.collection.ObjArrayList;
import io.arabesque.utils.collection.AtomicBitSetArray;
import com.koloboke.collect.IntCollection;
import com.koloboke.collect.set.hash.HashIntSet;
import com.koloboke.collect.map.hash.HashIntObjMap;

import java.io.Externalizable;

public interface Embedding extends WritableObject, Externalizable {
    void init(Configuration configuration);

    IntArrayList getWords();

    IntArrayList getVertices();
    
    <V> Vertex<V> vertex(int vertexId);

    int getNumVertices();

    IntArrayList getEdges();
    
    <E> Edge<E> edge(int edgeId);
    
    <E> LabelledEdge<E> labelledEdge(int edgeId);
    
    int getNumEdges();

    int getNumWords();

    Pattern getPattern();

    int getNumVerticesAddedWithExpansion();

    int getNumEdgesAddedWithExpansion();

    void addWord(int word);

    int getLastWord();
    
    void removeLastWord();

    IntCollection getExtensibleWordIds(Computation computation);
    
    IntCollection extensions();
    
    IntCollection extensions(Computation computation);
    
    IntCollection extensions(Computation computation, Pattern pattern);
    
    boolean isCanonicalEmbeddingWithWord(int wordId);

    String toOutputString();
    
    void nextExtensionLevel();
    
    void nextExtensionLevel(Embedding other);
    
    void previousExtensionLevel();

    void applyTagFrom(Computation computation,
          AtomicBitSetArray vtag, AtomicBitSetArray etag, int pos);
    
    void applyTagTo(Computation computation,
          AtomicBitSetArray vtag, AtomicBitSetArray etag, int pos);

   HashIntObjMap cacheStore();
}
