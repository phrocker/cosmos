package cosmos.util.sql.impl;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.log4j.Logger;

import com.google.common.base.Function;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import cosmos.Cosmos;
import cosmos.UnexpectedStateException;
import cosmos.UnindexedColumnException;
import cosmos.impl.SortableResult;
import cosmos.results.Column;
import cosmos.results.impl.MultimapQueryResult;
import cosmos.util.sql.call.ChildVisitor;
import cosmos.util.sql.call.Field;
import cosmos.util.sql.call.Literal;
import cosmos.util.sql.call.Pair;
import cosmos.util.sql.call.impl.FieldEquality;
import cosmos.util.sql.call.impl.operators.AndOperator;
import cosmos.util.sql.call.impl.operators.OrOperator;

public class LogicVisitor implements
		Function<ChildVisitor, Iterable<MultimapQueryResult>> {

	private SortableResult sortRes;
	private Cosmos cosmosRef;

	private static final Logger log = Logger.getLogger(LogicVisitor.class);

	protected static final Cache<String, Entry<Cosmos, SortableResult>> tempTableCache;

	static {

		RemovalListener<String, Entry<Cosmos, SortableResult>> removalListener = new RemovalListener<String, Entry<Cosmos, SortableResult>>() {
			public void onRemoval(
					RemovalNotification<String, Entry<Cosmos, SortableResult>> removal) {
				Entry<Cosmos, SortableResult> entry = removal.getValue();

				try {
					entry.getKey().delete(entry.getValue());
				} catch (MutationsRejectedException e) {
					log.error(e);
				} catch (TableNotFoundException e) {
					log.error(e);
				} catch (UnexpectedStateException e) {
					log.error(e);
				}

			}
		};
		tempTableCache = CacheBuilder.newBuilder()
				.expireAfterAccess(2, TimeUnit.HOURS)
				.removalListener(removalListener).build();
	}

	public LogicVisitor(Cosmos cosmos, SortableResult res) {
		this.sortRes = res;
		this.cosmosRef = cosmos;
	}

	@Override
	public Iterable<MultimapQueryResult> apply(ChildVisitor input) {

		HashFunction hf = Hashing.md5();
		HashCode hc = hf.newHasher().putObject(input, input).hash();
		System.out.println(hc.toString());

		Entry<Cosmos, SortableResult> entry = tempTableCache.getIfPresent(hc
				.toString());

		if (null != entry) {
			System.out.println("reusing " + hc.toString());
			try {
				return entry.getKey().fetch(entry.getValue());
			} catch (TableNotFoundException e) {
				log.error(e);
			} catch (UnexpectedStateException e) {
				log.error(e);
			}
		}

		Iterable<MultimapQueryResult> iter = Collections.emptyList();
		if (input instanceof FieldEquality) {
			FieldEquality equality = (FieldEquality) input;
			iter = apply(equality);
		} else if (input instanceof AndOperator) {
			AndOperator andOp = (AndOperator) input;
			iter = apply(andOp);

		} else if (input instanceof OrOperator) {
			OrOperator orOp = (OrOperator) input;

			iter = apply(orOp);
		}

		return iter;
	}

	protected Iterable<MultimapQueryResult> apply(AndOperator andOp) {

		Iterable<MultimapQueryResult> iter = Collections.emptyList();
		Iterable<ChildVisitor> children = Iterables.filter(andOp.getChildren(),
				new FilterFilter());
		Iterator<ChildVisitor> childIter = children.iterator();
		if (childIter.hasNext()) {
			iter = apply((FieldEquality) childIter.next());
		}

		while (childIter.hasNext()) {
			FieldEquality equality = (FieldEquality) childIter.next();
			for (ChildVisitor child : equality.getChildren()) {
				Pair<Field, Literal> entry = (Pair<Field, Literal>) child;
				iter = Iterables.filter(iter, new DocumentFieldPredicate(
						(Field) entry.first(), (Literal) entry.second()));
			}

		}

		return iter;
	}

	protected Iterable<MultimapQueryResult> apply(OrOperator andOp) {

		Iterable<MultimapQueryResult> iter = Collections.emptyList();
		Iterable<ChildVisitor> children = Iterables.filter(andOp.getChildren(),
				new FilterFilter());
		Iterator<ChildVisitor> childIter = children.iterator();

		while (childIter.hasNext()) {
			FieldEquality equality = (FieldEquality) childIter.next();

			iter = Iterables.concat(iter, apply(equality));

		}

		SortableResult meatadata = new SortableResult(sortRes.connector(),
				sortRes.auths(), sortRes.columnsToIndex());

		try {
			cosmosRef.register(meatadata);
			cosmosRef.addResults(meatadata, iter);
			iter = Collections.emptyList();

			HashFunction hf = Hashing.md5();
			HashCode hc = hf.newHasher().putObject(andOp, andOp).hash();
			System.out.println(hc.toString());

			tempTableCache.put(hc.toString(),
					Maps.immutableEntry(cosmosRef, meatadata));

			return cosmosRef.fetch(meatadata);
		} catch (Exception e) {
			log.error(e);
		}

		return iter;
	}

	protected Iterable<MultimapQueryResult> apply(FieldEquality equality) {
		Iterable<MultimapQueryResult> iter = Collections.emptyList();
		for (ChildVisitor child : equality.getChildren()) {
			Pair<Field, Literal> entry = (Pair<Field, Literal>) child;
			cosmos.util.sql.call.Field field = (Field) entry.first();
			cosmos.util.sql.call.Literal literal = (Literal) entry.second();
			try {
				iter = Iterables.concat(cosmosRef.fetch(sortRes, new Column(
						field.toString()), literal.toString()));
			} catch (TableNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (UnexpectedStateException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (UnindexedColumnException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return iter;

	}

}