package com.spintogether;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
@Controller public class RoomPageController { @GetMapping("/room/{code}") String room(){ return "forward:/index.html"; } }
