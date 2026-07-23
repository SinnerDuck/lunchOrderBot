package kz.dmp.freedomdmplunch.component;

import kz.dmp.freedomdmplunch.services.GeminiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.File;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.polls.PollAnswer;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll;
import org.telegram.telegrambots.meta.api.methods.polls.StopPoll;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LunchBot extends TelegramLongPollingBot {

    private final String botUsername;
    private final GeminiService geminiService;
    private Integer activeTopicId = 230;
    private final java.io.File usersFile = new java.io.File("lunch_users_db.txt");
    // Файл для сохранения ID топика
    private final java.io.File topicFile = new java.io.File("lunch_topic_db.txt");
    // pollId -> список вариантов ответа
    private final Map<String, List<String>> pollStorage = new ConcurrentHashMap<>();

    // userId -> (pollId -> список выбранных блюд)
    private final Map<Long, Map<String, List<String>>> userOrders = new ConcurrentHashMap<>();

    // userId -> Имя пользователя (чтобы красиво выводить)
    private final Map<Long, String> userNames = new ConcurrentHashMap<>();

    // База всех коллег: userId -> Имя
    private final Map<Long, String> knownUsers = new ConcurrentHashMap<>();

    // pollId -> messageId (нужно для остановки опроса)
    private final Map<String, Integer> pollMessageIds = new ConcurrentHashMap<>();

    // pollId -> Название категории (в нижнем регистре для удобного поиска)
    private final Map<String, String> pollCategories = new ConcurrentHashMap<>();

    public LunchBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername, GeminiService geminiService) {
        super(botToken);
        this.botUsername = botUsername;
        this.geminiService = geminiService;
        loadKnownUsers();
        loadTopicId();
    }

    private void loadKnownUsers() {
        if (!usersFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(usersFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    knownUsers.put(Long.parseLong(parts[0]), parts[1]);
                }
            }
            log.info("Загружено {} пользователей из базы.", knownUsers.size());
        } catch (Exception e) {
            log.error("Ошибка загрузки пользователей: ", e);
        }
    }

    private void rememberUser(Long userId, String fullName) {
        if (knownUsers.containsKey(userId)) {
            return;
        }
        knownUsers.put(userId, fullName);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(usersFile, true))) {
            writer.write(userId + "=" + fullName + "\n");
        } catch (Exception e) {
            log.error("Ошибка сохранения пользователя: ", e);
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void onUpdateReceived(Update update) {
        // 1. ОБРАБОТКА ТЕКСТОВЫХ КОМАНД И ФОТО (то что уже есть)
        if (update.hasMessage()) {
            Integer messageThreadId = update.getMessage().getMessageThreadId();
            long chatId = update.getMessage().getChatId();
            String text = update.getMessage().hasText() ? update.getMessage().getText() : "";

            // Разрешаем команду /bind слушать везде, а остальное игнорируем, если топик не совпадает
            if (activeTopicId != null && !activeTopicId.equals(messageThreadId) && !text.startsWith("/bind")) {
                return;
            }

            // Запоминаем пользователя
            org.telegram.telegrambots.meta.api.objects.User from = update.getMessage().getFrom();
            String name = (from.getFirstName() + " " + (from.getLastName() != null ? from.getLastName() : "")).trim();
            rememberUser(from.getId(), name);

            if (update.getMessage().hasText()) {
                String command = text.split("@")[0].toLowerCase();

                switch (command) {
                    // НОВАЯ КОМАНДА ПРИВЯЗКИ
                    case "/bind" -> {
                        if (messageThreadId != null) {
                            saveTopicId(messageThreadId);
                            sendMessage(chatId, "🔗 Бот успешно привязан к этому топику! Теперь я работаю только здесь.");
                        } else {
                            sendMessage(chatId, "⚠️ Эту команду нужно вызывать внутри конкретного топика (ветки), а не в общем чате.");
                        }
                    }
                    case "/start" -> sendMessage(chatId, "Привет! Отправь мне фото меню на сегодня.");
                    case "/collect" -> sendAggregatedOrders(chatId);
                    case "/tagall", "/all" -> tagAllUsers(chatId);
                }
            } else if (update.getMessage().hasPhoto()) {
                sendMessage(chatId, "Вижу картинку! Начинаю обработку...");
                var photos = update.getMessage().getPhoto();
                var biggestPhoto = photos.getLast();
                String fileId = biggestPhoto.getFileId();
                java.io.File downloadedImage = downloadTelegramPhoto(fileId);

                if (downloadedImage != null) {
                    pollStorage.clear();
                    userOrders.clear();
                    pollMessageIds.clear();
                    pollCategories.clear();

                    // ... дальше идет geminiService.analyzeMenuImage ...

                    String menuJson = geminiService.analyzeMenuImage(downloadedImage);
                    sendMenuPolls(chatId, menuJson);
                    downloadedImage.delete();
                }
            }
        }
        // 2. ОБРАБОТКА ГОЛОСОВАНИЙ В ОПРОСАХ
        else if (update.hasPollAnswer()) {
            PollAnswer answer = update.getPollAnswer();
            String pollId = answer.getPollId();
            Long userId = answer.getUser().getId();

            // Сохраняем имя (Имя + Фамилия, если есть)
            String firstName = answer.getUser().getFirstName();
            String lastName = answer.getUser().getLastName() != null ? answer.getUser().getLastName() : "";
            String fullName = (answer.getUser().getFirstName() + " " + (answer.getUser().getLastName() != null ? answer.getUser().getLastName() : "")).trim();
            userNames.put(userId, (firstName + " " + lastName).trim());
            rememberUser(userId, fullName);

            // Достаем названия блюд по их индексам
            List<String> chosenDishes = new ArrayList<>();
            List<String> options = pollStorage.get(pollId);

            if (options != null) {
                for (Integer optionId : answer.getOptionIds()) {
                    String dishName = options.get(optionId);
                    // Игнорируем технические кнопки
                    if (!dishName.contains("Пропустить")) {
                        chosenDishes.add(dishName);
                    }
                }
            }

            // Обновляем заказ пользователя (Map сам заменит старый выбор в этом опросе, если человек переголосовал)
            userOrders.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                    .put(pollId, chosenDishes);
        }
    }

    private void sendMessage(long chatId, String text) {
        int maxMessageLength = 4000;

        try {
            for (int i = 0; i < text.length(); i += maxMessageLength) {
                int endIndex = Math.min(text.length(), i + maxMessageLength);
                String chunk = text.substring(i, endIndex);

                SendMessage message = new SendMessage();
                message.setChatId(String.valueOf(chatId));
                message.setText(chunk);
                if (activeTopicId != null) {
                    message.setMessageThreadId(activeTopicId);
                }

                execute(message);
            }
        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения в Telegram: ", e);
        }
    }

    private java.io.File downloadTelegramPhoto(String fileId) {
        try {
            GetFile getFileMethod = new GetFile();
            getFileMethod.setFileId(fileId);

            File telegramFile = execute(getFileMethod);

            return downloadFile(telegramFile);
        } catch (TelegramApiException e) {
            log.error(e.getMessage());
            return null;
        }
    }

    private void sendMenuPolls(long chatId, String jsonString) {
        try {
            JSONArray categoriesArray = new JSONArray(jsonString);

            // Проходимся по каждой категории
            for (int i = 0; i < categoriesArray.length(); i++) {
                JSONObject categoryObj = categoriesArray.getJSONObject(i);

                String categoryName = categoryObj.optString("category", "Без категории");
                JSONArray itemsArray = categoryObj.optJSONArray("items");

                // Если в категории нет блюд, пропускаем её
                if (itemsArray == null || itemsArray.isEmpty()) {
                    continue;
                }

                List<String> options = new ArrayList<>();

                // Проходимся по блюдам внутри категории
                for (int j = 0; j < itemsArray.length(); j++) {
                    JSONObject dish = itemsArray.getJSONObject(j);
                    String name = dish.optString("name", "Неизвестное блюдо");

                    String priceStr = "";
                    if (!dish.isNull("price")) {
                        priceStr = " - " + dish.getInt("price") + " тг";
                    }

                    String optionText = name + priceStr;

                    // Ограничение Telegram на длину варианта ответа
                    if (optionText.length() > 100) {
                        optionText = optionText.substring(0, 97) + "...";
                    }

                    options.add(optionText);
                }

                // Telegram требует минимум 2 варианта ответа
                if (options.size() < 2) {
                    options.add("Пропустить эту категорию");
                }

                // И максимум 10 вариантов. Обрезаем, если больше.
                if (options.size() > 10) {
                    options = options.subList(0, 10);
                }

                // Создаем и отправляем опрос
                SendPoll sendPoll = new SendPoll();
                sendPoll.setQuestion("🍽 " + categoryName);
                sendPoll.setOptions(options);
                sendPoll.setIsAnonymous(false);
                sendPoll.setAllowMultipleAnswers(true);
                sendPoll.setChatId(String.valueOf(chatId));
                if (activeTopicId != null) {
                    sendPoll.setMessageThreadId(activeTopicId);
                }

                try {
                    org.telegram.telegrambots.meta.api.objects.Message sentMessage = execute(sendPoll);

                    pollStorage.put(sentMessage.getPoll().getId(), options);
                    pollMessageIds.put(sentMessage.getPoll().getId(), sentMessage.getMessageId());

                    // Сохраняем название категории (приводим к нижнему регистру, чтобы было проще искать)
                    pollCategories.put(sentMessage.getPoll().getId(), categoryName.toLowerCase());
                } catch (TelegramApiException e) {
                    log.error("Ошибка при отправке опроса '{}': ", categoryName, e);
                }
            }

            sendMessage(chatId, "✅ Меню сформировано! Голосуйте.");

        } catch (Exception e) {
            log.error("Ошибка при парсинге JSON для опросов: ", e);
            sendMessage(chatId, "Не удалось создать опросы из-за ошибки в формате данных.");
        }
    }

    private void sendAggregatedOrders(long chatId) {
        // 1. ОСТАНАВЛИВАЕМ ВСЕ ОПРОСЫ
        for (Integer messageId : pollMessageIds.values()) {
            StopPoll stopPoll = new StopPoll();
            stopPoll.setChatId(String.valueOf(chatId));
            stopPoll.setMessageId(messageId);
            try {
                execute(stopPoll);
            } catch (TelegramApiException e) {
                log.error("Ошибка остановки опроса", e);
            }
        }
        pollMessageIds.clear();

        if (userOrders.isEmpty()) {
            sendMessage(chatId, "🤷‍♂️ Опросы закрыты. Никто ничего не заказал.");
            return;
        }

        // 2. СБОР ДАННЫХ И РАСПРЕДЕЛЕНИЕ БЛЮД (Комплекс / Отдельно)
        Map<String, Integer> regularCounts = new LinkedHashMap<>(); // Обычные порции
        Map<String, Integer> comboCounts = new LinkedHashMap<>();   // Порции в составе комбо
        Map<Long, String> userBillingLines = new LinkedHashMap<>(); // Строки для счета
        int totalSum = 0;

        for (Map.Entry<Long, Map<String, List<String>>> entry : userOrders.entrySet()) {
            Long userId = entry.getKey();

            // Теперь храним сами названия блюд, а не только их цены
            List<String> firsts = new ArrayList<>();
            List<String> seconds = new ArrayList<>();
            List<String> salads = new ArrayList<>();
            List<String> drinks = new ArrayList<>();
            List<String> others = new ArrayList<>();

            for (Map.Entry<String, List<String>> pollEntry : entry.getValue().entrySet()) {
                String pollId = pollEntry.getKey();
                String category = pollCategories.getOrDefault(pollId, "").toLowerCase();

                for (String dish : pollEntry.getValue()) {
                    if (category.contains("перв") && !category.contains("не входит в комп")) firsts.add(dish);
                    else if (category.contains("втор") && !category.contains("не входит в комп")) seconds.add(dish);
                    else if (category.contains("салат") && !category.contains("не входит в комп")) salads.add(dish);
                    else if ((category.contains("напит") || category.contains("домашн") || category.contains("прохлад"))
                            && !category.contains("не входит в комп")) drinks.add(dish);
                    else others.add(dish);
                }
            }

            // Сортируем списки по убыванию цены, чтобы в комбо ушли самые дорогие блюда
            Comparator<String> byPriceDesc = (d1, d2) -> Integer.compare(extractPrice(d2), extractPrice(d1));
            firsts.sort(byPriceDesc);
            seconds.sort(byPriceDesc);
            salads.sort(byPriceDesc);
            drinks.sort(byPriceDesc);

            int comboCount = Math.min(Math.min(firsts.size(), seconds.size()), Math.min(salads.size(), drinks.size()));
            int userSum = comboCount * 2700;

            // Распределяем блюда, которые стали частью комплекса
            for (int i = 0; i < comboCount; i++) {
                comboCounts.merge(firsts.get(i), 1, Integer::sum);
                comboCounts.merge(seconds.get(i), 1, Integer::sum);
                comboCounts.merge(salads.get(i), 1, Integer::sum);
                comboCounts.merge(drinks.get(i), 1, Integer::sum);
            }

            // Распределяем оставшиеся блюда (заказанные отдельно) и плюсуем их цену
            for (int i = comboCount; i < firsts.size(); i++) {
                regularCounts.merge(firsts.get(i), 1, Integer::sum);
                userSum += extractPrice(firsts.get(i));
            }
            for (int i = comboCount; i < seconds.size(); i++) {
                regularCounts.merge(seconds.get(i), 1, Integer::sum);
                userSum += extractPrice(seconds.get(i));
            }
            for (int i = comboCount; i < salads.size(); i++) {
                regularCounts.merge(salads.get(i), 1, Integer::sum);
                userSum += extractPrice(salads.get(i));
            }
            for (int i = comboCount; i < drinks.size(); i++) {
                regularCounts.merge(drinks.get(i), 1, Integer::sum);
                userSum += extractPrice(drinks.get(i));
            }
            for (String dish : others) {
                regularCounts.merge(dish, 1, Integer::sum);
                userSum += extractPrice(dish);
            }

            // Формируем чек для пользователя
            if (userSum > 0) {
                String name = userNames.get(userId);
                String comboMark = (comboCount > 0) ? " <i>(Скидка за Комбо x" + comboCount + "!)</i>" : "";
                userBillingLines.put(userId, "👤 " + name + ": <b>" + userSum + " тг</b>" + comboMark + "\n");
                totalSum += userSum;
            }
        }

        // 3. ФОРМИРУЕМ ЧЕК ДЛЯ КУХНИ (С пометкой о комплексах)
        StringBuilder kitchenOrder = new StringBuilder("👨‍🍳 <b>ОБЩИЙ ЗАКАЗ (ДЛЯ КУХНИ):</b>\n\n");

        // Используем LinkedHashSet, чтобы сохранить порядок вывода блюд
        Set<String> allUniqueDishes = new LinkedHashSet<>();
        allUniqueDishes.addAll(regularCounts.keySet());
        allUniqueDishes.addAll(comboCounts.keySet());

        for (String dish : allUniqueDishes) {
            int reg = regularCounts.getOrDefault(dish, 0);
            int combo = comboCounts.getOrDefault(dish, 0);
            int total = reg + combo;

            kitchenOrder.append("▫️ ").append(dish).append(" <b>x").append(total).append("</b>");

            // Проставляем теги для кухни
            if (combo > 0) {
                if (reg > 0) {
                    kitchenOrder.append(" <i>(В комплексе: ").append(combo).append(", Отдельно: ").append(reg).append(")</i>");
                } else {
                    kitchenOrder.append(" <i>(Все в комплексе)</i>");
                }
            }
            kitchenOrder.append("\n");
        }

        // 4. ФОРМИРУЕМ ЧЕК ПО ОПЛАТЕ
        StringBuilder billingInfo = new StringBuilder("💸 <b>К ОПЛАТЕ:</b>\n\n");
        for (String line : userBillingLines.values()) {
            billingInfo.append(line);
        }
        billingInfo.append("\n💰 <b>ИТОГО СБОР: ").append(totalSum).append(" тг</b>");

        SendMessage kitchenMsg = new SendMessage(String.valueOf(chatId), kitchenOrder.toString());
        kitchenMsg.setParseMode("HTML");
        if (activeTopicId != null) {
            kitchenMsg.setMessageThreadId(activeTopicId);
        }
        try {
            execute(kitchenMsg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки заказа", e);
        }

        SendMessage billingMsg = new SendMessage(String.valueOf(chatId), billingInfo.toString());
        billingMsg.setParseMode("HTML");
        if (activeTopicId != null) {
            billingMsg.setMessageThreadId(activeTopicId);
        }
        try {
            execute(billingMsg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки счета", e);
        }
    }

    // Вспомогательный метод парсинга цен
    private int extractPrice(String text) {
        // Добавили второй слеш перед d: (\\d+)
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(" - (\\d+) тг").matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    private void tagAllUsers(long chatId) {
        if (knownUsers.isEmpty()) {
            sendMessage(chatId, "🤷‍♂️ Я пока никого не знаю. Пусть коллеги напишут любое сообщение или проголосуют!");
            return;
        }

        StringBuilder mentionMessage = new StringBuilder("📣 <b>Обед готов к выбору! Все сюда:</b>\n\n");

        for (Map.Entry<Long, String> entry : knownUsers.entrySet()) {
            mentionMessage.append("<a href=\"tg://user?id=").append(entry.getKey()).append("\">")
                    .append(entry.getValue()).append("</a> ");
        }

        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(mentionMessage.toString());
        message.setParseMode("HTML");
        if (activeTopicId != null) {
            message.setMessageThreadId(activeTopicId);
        }
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка при теге пользователей: ", e);
        }
    }

    private void loadTopicId() {
        if (!topicFile.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(topicFile))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                activeTopicId = Integer.parseInt(line.trim());
                log.info("Загружен ID топика: {}", activeTopicId);
            }
        } catch (Exception e) {
            log.error("Ошибка загрузки ID топика: ", e);
        }
    }

    private void saveTopicId(Integer topicId) {
        this.activeTopicId = topicId;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(topicFile))) {
            writer.write(String.valueOf(topicId));
        } catch (Exception e) {
            log.error("Ошибка сохранения ID топика: ", e);
        }
    }
}