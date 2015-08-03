package org.funtastic.bucket;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BulkWriteOperation;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.WriteConcern;
import org.bson.types.ObjectId;

import static com.mongodb.QueryBuilder.start;

public class Producer {

    public static void main(String[] args) throws Exception {

        final MongoClient client = new MongoClient();
        final DB brokerDb = client.getDB("broker");
        final String queueName = "funqueue";

        DBCollection queue = brokerDb.getCollection(queueName);
        queue.createIndex(start("processing").is(1).and("bucket").is(1).get());

        int bucket = 0;
        int maxBuckets = 100;

        int count = 0;
        long start = System.currentTimeMillis();

        while (true) {

            BulkWriteOperation bulk = queue.initializeUnorderedBulkOperation();
            for (int q=0; q<100; q++) {

                BasicDBList list = new BasicDBList();
                for (int j = 0; j < 100; j++) {
                    DBObject o = new BasicDBObject();
                    o.put("messageId", new ObjectId());
                    list.add(o);
                }

                DBObject o = start("bucket").is("" + bucket).and("processing").is(false).get();
                o.put("batch", list);
//                queue.insert(o, WriteConcern.UNACKNOWLEDGED);
                bulk.insert(o);

                bucket++;
                if (bucket == maxBuckets) {
                    bucket = 0;
                }

                count += list.size();
            }
            bulk.execute(WriteConcern.ACKNOWLEDGED);

            if (count % 1000 == 0) {
                long now = System.currentTimeMillis();
                long duration = now - start;
                double rate = count / (1d * duration / 1000);
                System.out.println("rate: " + rate + "/sec");
            }
        }
    }
}
