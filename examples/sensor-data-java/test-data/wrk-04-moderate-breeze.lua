function read_txt_file(path)
    local file, errorMessage = io.open(path, "r")
    if not file then
        error("Could not read the file:" .. errorMessage .. "\n")
    end

    local content = file:read "*all"
    file:close()
    return content
end

init = function(args)
  local FileBody = read_txt_file("04-moderate-breeze.json")

  wrk.method = "POST"
  wrk.headers["Content-Type"] = "application/json"
  wrk.headers["Connection"] = "Keep-Alive"
  wrk.body = FileBody

end
