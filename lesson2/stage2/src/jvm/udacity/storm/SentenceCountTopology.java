package udacity.storm;

import backtype.storm.Config;
import backtype.storm.LocalCluster;
import backtype.storm.StormSubmitter;
import backtype.storm.task.ShellBolt;
import backtype.storm.topology.IRichBolt;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseBasicBolt;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;

import backtype.storm.utils.Utils;

import backtype.storm.task.TopologyContext;
import backtype.storm.topology.BasicOutputCollector;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.OutputCollector;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.lambdaworks.redis.RedisClient;
import com.lambdaworks.redis.RedisConnection;

import udacity.storm.spout.RandomSentenceSpout;

/**
 * This topology demonstrates how to count distinct sentences from
 * a stream of sentences.
 *
 * This is an example for Udacity Real Time Analytics Course - ud381
 *
 */
public class SentenceCountTopology {

  /**
   * Constructor - does nothing
   */
  private SentenceCountTopology() { }

  /**
   * A spout that emits a random sentence
   */
  static class SentenceSpout extends BaseRichSpout {

    // Random number generator
    private Random rnd;

    // To output tuples from spout to the next stage
    private SpoutOutputCollector collector;

    // For storing the list of Sentences to be fed into the topology
    private String[] sentenceList;

    @Override
    public void open(
        Map                     map,
        TopologyContext         topologyContext,
        SpoutOutputCollector    spoutOutputCollector)
    {

      // initialize the random number generator
      rnd = new Random(31);

      // save the output collector for emitting tuples
      collector = spoutOutputCollector;

      // initialize a set of sentences
      sentenceList = new String[]{"Jack", "Mary", "Jill", "McDonald"};
    }

    @Override
    public void nextTuple()
    {
      // sleep a second before emitting any sentence
      Utils.sleep(1000);

      // generate a random number based on the sentenceList length
      int nextInt = rnd.nextInt(sentenceList.length);

      // emit the sentence chosen by the random number from sentenceList
      collector.emit(new Values(sentenceList[nextInt]));
    }

    @Override
    public void declareOutputFields(
        OutputFieldsDeclarer outputFieldsDeclarer)
    {
      // tell storm the schema of the output tuple for this spout
      // tuple consists of a single column called 'sentence'
      outputFieldsDeclarer.declare(new Fields("sentence"));
    }
  }

  /**
   * A bolt that counts the sentences that it receives
   */
  static class CountBolt extends BaseRichBolt {

    // To output tuples from this bolt to the next stage bolts, if any
    private OutputCollector collector;

    // Map to store the count of the sentences
    private Map<String, Integer> countMap;

    @Override
    public void prepare(
        Map                     map,
        TopologyContext         topologyContext,
        OutputCollector         outputCollector)
    {

      // save the collector for emitting tuples
      collector = outputCollector;

      // create and initialize the map
      countMap = new HashMap<String, Integer>();
    }

    @Override
    public void execute(Tuple tuple)
    {
      //**************************************************
      //BEGIN YOUR CODE - Part 1-of-3
      //Check if incoming sentence is in countMap.  If sentence does not
      //exist then add sentence with count = 1, if sentence exist then
      //increment count.

      //Syntax to get the sentence from the 1st column of incoming tuple
      String sentence = tuple.getString(0);

      // check if the sentence is present in the map
      if (countMap.get(sentence) == null) {

      // not present, add the sentence with a count of 1
      countMap.put(sentence, 1);
      } else {

      // already there, hence get the count
      Integer val = countMap.get(sentence);

      // increment the count and save it to the map
      countMap.put(sentence, ++val);
    }

      //After countMap is updated, emit sentence and count to output collector
      // Syntax to emit the sentence and count (uncomment to emit)
      collector.emit(new Values(sentence, countMap.get(sentence)));

      //END YOUR CODE Part 1-of-3
      //***************************************************
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer)
    {
      // tell storm the schema of the output tuple for this spout
      // tuple consists of a two columns called 'sentence' and 'count'

      // declare the first column 'sentence', second colmun 'count'

      //****************************************************
      //BEGIN YOUR CODE - Part 2-of-3
      //uncomment line below to declare output

      outputFieldsDeclarer.declare(new Fields("sentence","count"));

      //END YOUR CODE Part 2-of-3
      //****************************************************
    }
  }

  /**
   * A bolt that prints the sentence and count to redis
   */
  static class ReportBolt extends BaseRichBolt
  {
    // place holder to keep the connection to redis
    transient RedisConnection<String,String> redis;

    @Override
    public void prepare(
        Map                     map,
        TopologyContext         topologyContext,
        OutputCollector         outputCollector)
    {
      // instantiate a redis connection
      RedisClient client = new RedisClient("localhost",6379);

      // initiate the actual connection
      redis = client.connect();
    }

    @Override
    public void execute(Tuple tuple)
    {
      // access the first column 'sentence'
      String sentence = tuple.getStringByField("sentence");

      // access the second column 'count'
      Integer count = tuple.getIntegerByField("count");

      // publish the sentence count to redis using sentence as the key
      redis.publish("SentenceCountTopology", sentence + "|" + Long.toString(count));
    }

    public void declareOutputFields(OutputFieldsDeclarer declarer)
    {
      // nothing to add - since it is the final bolt
    }
  }

  public static void main(String[] args) throws Exception
  {
    // create the topology
    TopologyBuilder builder = new TopologyBuilder();

    // attach the sentence spout to the topology - parallelism of 5
    builder.setSpout("sentence-spout", new RandomSentenceSpout(), 5);

    // attach the count bolt using fields grouping - parallelism of 15
    builder.setBolt("count-bolt", new CountBolt(), 15).fieldsGrouping("sentence-spout", new Fields("sentence"));

    // attach the report bolt using global grouping - parallelism of 1
    //***************************************************
    // BEGIN YOUR CODE - Part 3-of-3

    builder.setBolt("report-bolt", new ReportBolt(), 1).globalGrouping("count-bolt");


    // END YOUR CODE Part 3-of-3
    //***************************************************

    // create the default config object
    Config conf = new Config();

    // set the config in debugging mode
    conf.setDebug(true);

    if (args != null && args.length > 0) {

      // run it in a live cluster

      // set the number of workers for running all spout and bolt tasks
      conf.setNumWorkers(3);

      // create the topology and submit with config
      StormSubmitter.submitTopology(args[0], conf, builder.createTopology());

    } else {

      // run it in a simulated local cluster

      // set the number of threads to run - similar to setting number of workers in live cluster
      conf.setMaxTaskParallelism(3);

      // create the local cluster instance
      LocalCluster cluster = new LocalCluster();

      // submit the topology to the local cluster
      cluster.submitTopology("sentence-count", conf, builder.createTopology());

      //**********************************************************************
      // let the topology run for 30 seconds. note topologies never terminate!
      Thread.sleep(30000);
      //**********************************************************************

      // we are done, so shutdown the local cluster
      cluster.shutdown();
    }
  }
}
