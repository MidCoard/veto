package top.focess.veto.veto;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GBNFGrammarEngineTest {

  private GBNFGrammarEngine grammarEngine;

  @BeforeEach
  void setUp() {
    grammarEngine = new GBNFGrammarEngine(new VetoGatewayConfiguration());
  }

  @Test
  void testDefaultVetoGrammar() {
    String grammar = grammarEngine.getDefaultVetoGrammar();
    assertNotNull(grammar);
    assertTrue(grammar.contains("veto-response"));
    assertTrue(grammar.contains("veto_decision"));
    assertTrue(grammar.contains("\"pass\""));
    assertTrue(grammar.contains("\"redact\""));
    assertTrue(grammar.contains("\"block\""));
  }

  @Test
  void testCodeConstraintGrammar() {
    String grammar = grammarEngine.getCodeConstraintGrammar();
    assertNotNull(grammar);
    assertTrue(grammar.contains("code-constraint-response"));
    assertTrue(grammar.contains("violations"));
    assertTrue(grammar.contains("redacted"));
  }

  @Test
  void testSecretsRedactionGrammar() {
    String grammar = grammarEngine.getSecretsRedactionGrammar();
    assertNotNull(grammar);
    assertTrue(grammar.contains("redaction-response"));
    assertTrue(grammar.contains("secrets_found"));
    assertTrue(grammar.contains("redacted_fields"));
  }

  @Test
  void testRegisterCustomGrammar() {
    grammarEngine.registerGrammar("custom", "root ::= \"hello\"");
    String loaded = grammarEngine.loadGrammar("custom");
    assertEquals("root ::= \"hello\"", loaded);
  }

  @Test
  void testLoadNonexistentGrammarReturnsDefault() {
    String result = grammarEngine.loadGrammar("/nonexistent/path/grammar.gbnf");
    assertNotNull(result);
    assertTrue(result.contains("veto-response"));
  }
}
