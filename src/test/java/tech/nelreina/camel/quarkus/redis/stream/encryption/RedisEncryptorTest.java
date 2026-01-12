package tech.nelreina.camel.quarkus.redis.stream.encryption;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class RedisEncryptorTest {

    private String examplePayload = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><SwInt:ExchangeResponse xmlns:Sw=\"urn:swift:snl:ns.Sw\" xmlns:SwGbl=\"urn:swift:snl:ns.SwGbl\" xmlns:SwSec=\"urn:swift:snl:ns.SwSec\" xmlns:SwInt=\"urn:swift:snl:ns.SwInt\"><SwInt:SwiftRequestRef>SNL37486-2025-09-10T20:12:24.23639.057553Z</SwInt:SwiftRequestRef><SwSec:SignatureList><SwSec:Signature><SwSec:SignedInfo><Sw:Reference><Sw:DigestValue>STCPrTM0RyBgF6TIDRw4dLZRU4R9NuXG/eXjtku0As</SwSec:SignatureValue><SwSec:KeyInfo><SwSec:SignDN>cn=ecabagag-t,o=ecabagag,o=swift</SwSec:SignDN><SwSec:CertPolicyId>1.3.21.6.2</SwSec:CertPolicyId></SwSec:KeyInfo><SwSec:Manifest><Sw:Reference><Sw:DigestRef>Sw.RequestPayload</Sw:DigestRef><Sw:DigestValue>hynx2LtmPEeErsdL49NGaXnfoyfa8TS9Ty2VNfRJa8k=</Sw:DigestValue></Sw:Reference><Sw:Reference><Sw:DigestRef>Sw.E2S</Sw:DigestRef><Sw:DigestValue>aKlk/h1e0l/I0UCIwnxXvuO2VbExFdJHhWABBwJ8BOQ=</Sw:DigestValue></Sw:Reference></SwSec:Manifest></SwSec:Signature></SwSec:SignatureList><SwGbl:Status><SwGbl:StatusAttributes><SwGbl:Severity>Fatal</SwGbl:Severity><SwGbl:Code>SwReq.ValidationError</SwGbl:Code><SwGbl:Parameter>Level=1</SwGbl:Parameter><SwGbl:Parameter>pacs.008.001.08/pacs:FIToFICustomerCreditTransferV08_doc/FIToFICstmrCdtTrf/GrpHdr/MsgId</SwGbl:Parameter><SwGbl:Text>The value must match the specified pattern [0-9a-zA-Z/\\-\\?:\\(\\)\\.,&apos;\\+ ]+; contain a maximum of 35 character(s); contain a minimum of 1 character(s)</SwGbl:Text></SwGbl:StatusAttributes><SwGbl:StatusAttributes><SwGbl:Severity>Fatal</SwGbl:Severity><SwGbl:Code>SwReq.ValidationError</SwGbl:Code><SwGbl:Parameter>Level=1</SwGbl:Parameter><SwGbl:Parameter>pacs.008.001.08/pacs:FIToFICustomerCreditTransferV08_doc/FIToFICstmrCdtTrf/CdtTrfTxInf[1]/PmtId/InstrId</SwGbl:Parameter><SwGbl:Text>The value must match the specified pattern [0-9a-zA-Z/\\-\\?:\\(\\)\\.,&apos;\\+ ]+; contain a maximum of 16 character(s); contain a minimum of 1 character(s)</SwGbl:Text></SwGbl:StatusAttributes></SwGbl:Status></SwInt:ExchangeResponse><?xml version='1.0' encoding='UTF-8'?><SwInt:ExchangeRequest xmlns:Sw=\"urn:swift:snl:ns.Sw\" xmlns:SwGbl=\"urn:swift:snl:ns.SwGbl\" xmlns:SwSec=\"urn:swift:snl:ns.SwSec\" xmlns:SwInt=\"urn:swift:snl:ns.SwInt\"><SwSec:AuthorisationContext><SwSec:UserDN>cn=ecabagag-t,o=ecabagag,o=swift</SwSec:UserDN></SwSec:AuthorisationContext><SwInt:Request><SwInt:RequestControl><SwInt:RequestCrypto>TRUE</SwInt:RequestCrypto><SwInt:NRIndicator>FALSE</SwInt:NRIndicator><SwInt:DeliveryCtrl><SwInt:DeliveryMode>SnF</SwInt:DeliveryMode><SwInt:NotifQueue>ecabagag_finpluscur!p</SwInt:NotifQueue><Sw:DeliveryNotif>FALSE</Sw:DeliveryNotif><Sw:InputChannelCtrl><Sw:InputChannel>ecabagag_ic3!p</Sw:InputChannel><Sw:SnFInputSeq>183</Sw:SnFInputSeq><Sw:Token>A95/LzzyfjPFMeTpw2u0tOX1rElPV5sRqtzjQI2dzrQ=</Sw:Token></Sw:InputChannelCtrl><Sw:DeliveryNotificationViaSystemMessage>TRUE</Sw:DeliveryNotificationViaSystemMessage></SwInt:DeliveryCtrl><Sw:ProductList><Sw:ProductInfo><Sw:VendorName>PTSACHBB</Sw:VendorName><Sw:ProductName>IGTplus</Sw:ProductName><Sw:ProductVersion>24.0</Sw:ProductVersion></Sw:ProductInfo></Sw:ProductList><Sw:ReturnSignatureList>TRUE</Sw:ReturnSignatureList><Sw:RequestSubType>swift.cbprplus.stp.02</Sw:RequestSubType></SwInt:RequestControl><Sw:RequestE2EControl><Sw:MsgId>C86081900000XTWA</Sw:MsgId><Sw:CreationTime>2025-09-10T20:12:24Z</Sw:CreationTime></Sw:RequestE2EControl><SwInt:RequestHeader><SwInt:Requestor>ou=xxx,o=ecabagag,o=swift</SwInt:Requestor><SwInt:Responder>ou=xxx,o=filbjmkn,o=swift</SwInt:Responder><SwInt:Service>swift.finplus!pc</SwInt:Service><SwInt:RequestType>pacs.008.001.08</SwInt:RequestType><SwInt:Priority>Normal</SwInt:Priority><SwInt:RequestRef>IFACT_20250910221223680072</SwInt:RequestRef></SwInt:RequestHeader><SwInt:RequestPayload> <head:AppHdr xmlns:head=\"urn:i</pacs:Document> </SwInt:RequestPayload><SwSec:SignatureList><SwSec:Signature><SwSec:KeyInfo><SwSec:SignDN>cn=ecabagag-t,o=ecabagag,o=swift</SwSec:SignDN></SwSec:KeyInfo><SwSec:Manifest><Sw:Reference><Sw:DigestRef>Sw.RequestPayload</Sw:DigestRef></Sw:Reference></SwSec:Manifest></SwSec:Signature></SwSec:SignatureList></SwInt:Request></SwInt:ExchangeRequest>";
    private String encryptionKey = "vL9eA3rTqX5mZ8pKc2WbN7gYd4JhR6uQ";
    private String encryptionIv = "aB3dE6gH9jK1LmNp";

    @Test
    public void testEncryptAndDecrypt() {
        RedisEncryptor redisEncryptor = new RedisEncryptor(encryptionKey, encryptionIv, true);

        String encrypted = redisEncryptor.encrypt(examplePayload);

        Assertions.assertEquals(examplePayload, new RedisEncryptor(encryptionKey, encryptionIv, true).decrypt(encrypted));
    }

    @Test
    public void testEncryptAndDecryptDisabled() {
        RedisEncryptor redisEncryptor = new RedisEncryptor(encryptionKey, encryptionIv, false);

        String encrypted = redisEncryptor.encrypt(examplePayload);

        Assertions.assertEquals(examplePayload, new RedisEncryptor(encryptionKey, encryptionIv, false)
                .decrypt(encrypted));
    }

    @Test
    public void testInvalidInput() {
        //fail
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RedisEncryptor("            ", "           ", true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RedisEncryptor("", "", true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RedisEncryptor("", "aB3dE6gH9jK1LmNp", true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RedisEncryptor("vL9eA3rTqX5mZ8pKc2WbN7gYd4JhR6uQ", " ", true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RedisEncryptor("vL9eA3rTqX5mZ8pKc2WbN7gYd4JhR6uQ", "aB3dE6gH9jK1LmN", true));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RedisEncryptor("vL9eA3rTqX5mZ8pKc2WbN7gYd4JhR6u", "aB3dE6gH9jK1LmNp", true));

        //success
        Assertions.assertDoesNotThrow(() -> new RedisEncryptor(" ", "", false));
        Assertions.assertDoesNotThrow(() -> new RedisEncryptor(encryptionKey, encryptionIv, true));
    }
}
