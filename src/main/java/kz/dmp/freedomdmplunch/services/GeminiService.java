package kz.dmp.freedomdmplunch.services;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.Base64;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String apiKey;

    @Value("${gemini.api.url}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public String analyzeMenuImage(File imageFile) {
        try {
            // 1. Кодируем картинку в Base64
            byte[] fileContent = Files.readAllBytes(imageFile.toPath());
            String encodedString = Base64.getEncoder().encodeToString(fileContent);

            String promptText = "Распознай меню на картинке. Верни ответ СТРОГО в формате JSON. " +
                    "Это должен быть массив объектов, где каждый объект — это отдельная категория меню. " +
                    "У каждого объекта должно быть поле 'category' (название категории) и поле 'items' (массив блюд в этой категории). " +
                    "Внутри массива 'items' у каждого блюда должны быть поля: name (название), price (цена числом). " +
                    "ПРАВИЛО 1 (Заголовки): Если цена указана в заголовке категории, присвой эту цену каждому блюду в категории, если у них нет своей индивидуальной цены. " +
                    "ПРАВИЛО 2 (Выпечка): Если категория называется 'ВЫПЕЧКА', то для всех позиций, у которых в тексте нет явно указанной цены, установи цену 350. Если цена указана явно (например, 'Мини пицца - 599 тг'), используй указанную цену. " +
                    "Никакого лишнего текста или разметки markdown, только голый JSON.";

            JSONObject dataPart = new JSONObject();
            dataPart.put("mime_type", "image/jpeg");
            dataPart.put("data", encodedString);

            JSONObject inlineData = new JSONObject();
            inlineData.put("inline_data", dataPart);

            JSONObject textPart = new JSONObject();
            textPart.put("text", promptText);

            JSONArray partsArray = new JSONArray();
            partsArray.put(textPart);
            partsArray.put(inlineData);

            JSONObject contentObj = new JSONObject();
            contentObj.put("parts", partsArray);

            JSONArray contentsArray = new JSONArray();
            contentsArray.put(contentObj);

            JSONObject requestBody = new JSONObject();
            requestBody.put("contents", contentsArray);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(requestBody.toString(), headers);

            String fullUrl = UriComponentsBuilder.fromUri(URI.create(apiUrl))
                    .queryParam("key", apiKey)
                    .toUriString();

            String response = restTemplate.postForObject(fullUrl, request, String.class);
            return extractJsonFromResponse(response);

        } catch (Exception e) {
            e.printStackTrace();
            return "Ошибка обработки изображения: " + e.getMessage();
        }
    }

    private String extractJsonFromResponse(String rawResponse) {
        try {
            JSONObject json = new JSONObject(rawResponse);
            return json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
                    .replaceAll("^```json\\n?", "")
                    .replaceAll("\\n?```$", "");
        } catch (Exception e) {
            return "Не удалось распарсить ответ API: " + rawResponse;
        }
    }
}