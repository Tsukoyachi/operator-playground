package com.example.operator;

import io.javaoperatorsdk.operator.springboot.starter.test.EnableMockOperator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnableMockOperator(crdPaths = "classpath:META-INF/fabric8/applicationresources.com.example.operator-v1.yml")
class OperatorApplicationTests {

	@Test
	void contextLoads() {
	}

}
